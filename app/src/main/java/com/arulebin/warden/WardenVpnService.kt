package com.arulebin.warden

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.arulebin.egressledger.capture.FlowKey
import com.arulebin.egressledger.capture.Packets
import com.arulebin.egressledger.capture.TcpReset
import com.arulebin.egressledger.ledger.Decision
import com.arulebin.egressledger.ledger.EgressInspector
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local VPN that filters DNS over both IPv4 and IPv6, in one of two modes:
 *
 * - DNS-only (default): the system is told to use a virtual DNS server in each
 *   family and only those addresses are routed into the tunnel. Blocklisted
 *   domains get NXDOMAIN; the rest are forwarded to the configured upstream
 *   resolver over a protect()-ed socket.
 * - Full tunnel (AI egress ledger on, Android 10+): every route goes into the
 *   tunnel. DNS is still answered here exactly as above; everything else passes
 *   the ledger's inspector and is then relayed by [FullTunnel].
 */
class WardenVpnService : VpnService() {

    private var tunnel: ParcelFileDescriptor? = null
    private var out: FileOutputStream? = null
    private var readThread: Thread? = null
    private var forwarders: ExecutorService? = null
    private val writeLock = Any()

    /** Set only in full-tunnel mode. */
    private var fullTunnel: FullTunnel? = null
    private var inspector: EgressInspector? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        Rules.init(this)
        Stats.init(this)
        Egress.init(this)
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn()
                return START_NOT_STICKY
            }
            // A mode change (ledger on/off) needs different routes, which only a fresh tunnel can have.
            ACTION_RESTART -> if (tunnel != null) teardown() else return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (tunnel != null) return

        inspector = Egress.newInspector(this)
        val full = if (inspector != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Bring the relay up before routing everything to it; if it can't start, fall
            // back to DNS-only rather than establishing a tunnel that goes nowhere.
            FullTunnel(this) { buf, n -> writePacket(buf, n) }.takeIf { it.start() }
        } else {
            null
        }
        fullTunnel = full

        val fd = try {
            Builder()
                .setSession("Warden")
                .addAddress(TUN_V4, 24)
                .addDnsServer(DNS_V4)
                .addAddress(TUN_V6, 64)
                .addDnsServer(DNS_V6)
                .apply {
                    if (full != null) {
                        setMtu(FullTunnel.MTU)
                        addRoute("0.0.0.0", 0)
                        addRoute("::", 0)
                        // Warden's own sockets (relay, upstream DNS, list downloads) go straight out.
                        addDisallowedApplication(packageName)
                    } else {
                        addRoute(DNS_V4, 32)
                        addRoute(DNS_V6, 128)
                    }
                }
                .setBlocking(true)
                .establish()
        } catch (e: Exception) {
            null
        }

        if (fd == null) {
            full?.stop()
            fullTunnel = null
            WardenState.running = false
            stopSelf()
            return
        }
        tunnel = fd
        startForegroundWithNotification()
        WardenState.running = true
        WardenState.ledgerActive = full != null
        forwarders = Executors.newCachedThreadPool()
        readThread = Thread({ runPacketLoop(fd) }, "warden-dns").also { it.start() }
    }

    private fun runPacketLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        out = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(MAX_PACKET)
        // Don't answer anything until the big lists are in memory, or early lookups slip through.
        Rules.awaitLoaded()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buffer)
                if (n <= 0) continue
                handlePacket(buffer, n)
            }
        } catch (e: Exception) {
            // tunnel closed / interrupted — normal during stop
        }
    }

    /** [packet] is the loop's reused buffer: anything kept past this call must be copied. */
    private fun handlePacket(packet: ByteArray, length: Int) {
        val query = DnsUtils.parseDnsQuery(packet, length)
        if (query != null) {
            handleDns(query)
            return
        }
        val stack = fullTunnel ?: return
        // QUIC is held back: apps fall back to TCP, where the ClientHello names the endpoint.
        if (Packets.udpDstPort(packet, length) == 443) return
        val inspection = inspector?.onTcpPacket(packet, length)
        if (inspection != null) Egress.flush()
        if (inspection?.decision == Decision.DROP) {
            resetFlow(packet, length, stack)
            return
        }
        stack.toStack(packet, length)
    }

    /** Ends a blocked connection at its ClientHello: the app sees a reset, the stack drops its half. */
    private fun resetFlow(packet: ByteArray, length: Int, stack: FullTunnel) {
        val seg = Packets.parseTcp(packet, length) ?: return
        writePacket(TcpReset.toClient(seg))
        TcpReset.toServer(seg).let { stack.toStack(it, it.size) }
    }

    private fun handleDns(query: DnsUtils.DnsQuery) {
        // Encrypted-DNS endpoints are refused even if allowlisted: reaching one would skip every other rule.
        val filtered = SearchGuard.isBypass(query.domain) || Rules.isBlocked(query.domain)
        // In DNS-only mode the lookup is the ledger's only signal. With the full tunnel the
        // ClientHello is the record (one per real connection, unaffected by DNS caching).
        val egressDrop = !filtered && fullTunnel == null &&
            inspector?.onDnsQuery(flowOf(query), query.domain)
                ?.also { Egress.flush() }?.decision == Decision.DROP
        val blocked = filtered || egressDrop
        Stats.record(blocked)
        QueryLog.add(query.domain, blocked)
        val safeHost = if (blocked) null else SearchGuard.safeTarget(query.domain)
        when {
            blocked -> {
                val dns = DnsUtils.buildNxDomainResponse(query.dnsMessage)
                writePacket(DnsUtils.buildResponsePacket(query, dns))
            }
            safeHost != null -> forwarders?.execute { answerWithSafeHost(query, safeHost) }
            else -> forwarders?.execute { forwardUpstream(query) }
        }
    }

    private fun flowOf(q: DnsUtils.DnsQuery) = FlowKey(
        Packets.PROTO_UDP,
        InetAddress.getByAddress(q.srcIp), q.srcPort,
        InetAddress.getByAddress(q.dstIp), q.dstPort,
    )

    private class CachedIps(val ips: List<ByteArray>, val expiresAt: Long)
    private val safeCache = ConcurrentHashMap<String, CachedIps>()

    /**
     * Answers a search/video hostname with the addresses of its SafeSearch host.
     * Only A records are served; AAAA and HTTPS queries get an empty answer so
     * clients fall back to the IPv4 SafeSearch address instead of a native one.
     */
    private fun answerWithSafeHost(query: DnsUtils.DnsQuery, safeHost: String) {
        if (query.qtype != DnsUtils.TYPE_A) {
            writePacket(DnsUtils.buildResponsePacket(query, DnsUtils.buildAnswerResponse(query, emptyList())))
            return
        }
        val now = System.currentTimeMillis()
        val ips = safeCache[safeHost]?.takeIf { it.expiresAt > now }?.ips
            ?: resolveA(safeHost).also {
                if (it.isNotEmpty()) safeCache[safeHost] = CachedIps(it, now + SAFE_CACHE_MS)
            }
        // If the safe host can't be resolved, stay silent (the client retries) rather than
        // falling back to the unfiltered site.
        if (ips.isEmpty()) return
        writePacket(DnsUtils.buildResponsePacket(query, DnsUtils.buildAnswerResponse(query, ips)))
    }

    private fun resolveA(name: String): List<ByteArray> {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            val msg = DnsUtils.buildQuery((System.nanoTime() and 0xFFFF).toInt(), name, DnsUtils.TYPE_A)
            socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(Prefs.upstreamDns), 53))
            val buf = ByteArray(MAX_PACKET)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            DnsUtils.parseARecords(buf.copyOf(reply.length))
        } catch (e: Exception) {
            emptyList()
        } finally {
            socket?.close()
        }
    }

    private fun forwardUpstream(query: DnsUtils.DnsQuery) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            val upstream = InetAddress.getByName(Prefs.upstreamDns)
            socket.send(DatagramPacket(query.dnsMessage, query.dnsMessage.size, upstream, 53))
            val buf = ByteArray(MAX_PACKET)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            writePacket(DnsUtils.buildResponsePacket(query, buf.copyOf(reply.length)))
        } catch (e: Exception) {
            // upstream timeout/failure — drop; the client retries
        } finally {
            socket?.close()
        }
    }

    private fun writePacket(packet: ByteArray, length: Int = packet.size) {
        val o = out ?: return
        synchronized(writeLock) {
            try {
                o.write(packet, 0, length)
                o.flush()
            } catch (e: Exception) {
                // tunnel gone
            }
        }
    }

    private fun stopVpn() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        readThread?.interrupt()
        readThread = null
        forwarders?.shutdownNow()
        forwarders = null
        try { out?.close() } catch (_: Exception) {}
        out = null
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        fullTunnel?.stop()
        fullTunnel = null
        inspector = null
        Egress.flush(force = true)
        Stats.persist()
        WardenState.running = false
        WardenState.ledgerActive = false
    }

    override fun onRevoke() = stopVpn()

    override fun onDestroy() {
        super.onDestroy()
        teardown()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Warden protection", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WardenVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Warden is on")
            .setContentText(
                if (fullTunnel != null) "Filtering DNS and recording apps' AI traffic"
                else "Filtering DNS to block unwanted sites",
            )
            .setSmallIcon(R.drawable.ic_warden_shield)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_warden_shield, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.arulebin.warden.action.CONNECT"
        const val ACTION_DISCONNECT = "com.arulebin.warden.action.DISCONNECT"
        const val ACTION_RESTART = "com.arulebin.warden.action.RESTART"

        private const val CHANNEL_ID = "warden_vpn"
        private const val NOTIFICATION_ID = 1
        private const val TUN_V4 = "10.215.173.1"
        private const val DNS_V4 = "10.215.173.2"
        private const val TUN_V6 = "fd00:2:fd00::1"
        private const val DNS_V6 = "fd00:2:fd00::2"
        private const val UPSTREAM_TIMEOUT_MS = 5000
        private const val SAFE_CACHE_MS = 10 * 60 * 1000L
        private const val MAX_PACKET = 32767
    }
}
