package com.arulebin.warden

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileDescriptor

/**
 * Full-tunnel mode, used while the AI egress ledger is on. The system routes all
 * traffic into the VPN; Warden's read loop still sees every packet first, and what
 * it lets through is handed to a userspace TCP/IP stack ([TProxy]) over a
 * SEQPACKET socket pair:
 *
 *   tun → Warden (DNS filter, ledger, QUIC hold-back) → pair → stack → [Socks5Server] → network
 *   network → [Socks5Server] → stack → pair → Warden → tun
 *
 * Feeding the stack through Warden, instead of giving it the tun fd, keeps the app's
 * original 5-tuple in view — which getConnectionOwnerUid needs and a SOCKS session no
 * longer carries.
 *
 * Both ends of the pair are non-blocking: the stack runs every connection on one
 * cooperative task thread and must never block in read(). Warden's end waits in poll().
 */
class FullTunnel(private val service: VpnService, private val toTun: (ByteArray, Int) -> Unit) {
    private var ours: FileDescriptor? = null
    private var theirs: ParcelFileDescriptor? = null
    private var socks: Socks5Server? = null
    private var reader: Thread? = null
    @Volatile private var running = false

    /** Brings the stack up. Returns false (and leaves nothing running) if any step fails. */
    @RequiresApi(Build.VERSION_CODES.Q) // SOCK_NONBLOCK on the pair; the ledger is 29+ anyway
    fun start(): Boolean = try {
        val a = FileDescriptor()
        val b = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET or OsConstants.SOCK_NONBLOCK, 0, a, b)
        for (fd in listOf(a, b)) {
            // Room for bursts in both directions; a full buffer means a dropped packet, which TCP recovers.
            Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, SOCKET_BUFFER)
            Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, SOCKET_BUFFER)
        }
        ours = a
        theirs = ParcelFileDescriptor.dup(b).also { Os.close(b) }

        val server = Socks5Server(
            protect = { service.protect(it) },
            protectUdp = { service.protect(it) },
        )
        val port = server.start()
        socks = server

        val config = File(service.cacheDir, "tproxy.yml")
        config.writeText(
            """
            tunnel:
              mtu: $MTU
            socks5:
              port: $port
              address: ${Socks5Server.LOOPBACK_V4.hostAddress}
              udp: 'udp'
            misc:
              log-level: warn
            """.trimIndent(),
        )
        running = true
        check(TProxy.TProxyStartService(config.absolutePath, theirs!!.fd)) { "stack did not start" }
        reader = Thread(::pumpToTun, "warden-stack").also { it.start() }
        true
    } catch (e: Exception) {
        stop()
        false
    }

    /** One packet from the tunnel to the stack. Dropped if the stack is not keeping up. */
    fun toStack(packet: ByteArray, length: Int) {
        val fd = ours ?: return
        try {
            Os.write(fd, packet, 0, length)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EAGAIN && waitFor(fd, OsConstants.POLLOUT.toShort(), 100)) {
                try { Os.write(fd, packet, 0, length) } catch (_: ErrnoException) {}
            }
        } catch (_: Exception) {
            // pair closed during stop
        }
    }

    private fun pumpToTun() {
        val fd = ours ?: return
        val buf = ByteArray(MTU + 64)
        while (running) {
            if (!waitFor(fd, OsConstants.POLLIN.toShort(), 500)) continue
            val n = try {
                Os.read(fd, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) continue else break
            } catch (e: Exception) {
                break
            }
            if (n > 0) toTun(buf, n)
        }
    }

    private fun waitFor(fd: FileDescriptor, events: Short, timeoutMs: Int): Boolean {
        val pfd = StructPollfd().apply { this.fd = fd; this.events = events }
        return try {
            Os.poll(arrayOf(pfd), timeoutMs) > 0 && (pfd.revents.toInt() and events.toInt()) != 0
        } catch (_: ErrnoException) {
            false
        }
    }

    fun stop() {
        running = false
        try { TProxy.TProxyStopService() } catch (_: Throwable) {}
        socks?.stop()
        socks = null
        reader?.join(1_000)
        reader = null
        try { theirs?.close() } catch (_: Exception) {}
        theirs = null
        ours?.let { try { Os.close(it) } catch (_: Exception) {} }
        ours = null
    }

    companion object {
        /** Large frames cut per-packet overhead through the pair; the tun is given the same MTU. */
        const val MTU = 8500
        private const val SOCKET_BUFFER = 1 shl 20
    }
}
