package com.arulebin.warden

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A minimal SOCKS5 server (RFC 1928, no authentication) on the loopback address,
 * serving exactly one client: the tunnel's userspace stack ([TProxy]). Every app
 * connection that leaves the device in full-tunnel mode is one CONNECT here; every
 * UDP flow is one UDP ASSOCIATE.
 *
 * Outbound sockets are passed through [protect] so they leave via the real network
 * instead of looping back into the VPN. Plain blocking sockets with a thread per
 * direction: simple, and a phone holds hundreds of connections, not tens of thousands.
 */
class Socks5Server(
    private val protect: (Socket) -> Unit = {},
    private val protectUdp: (DatagramSocket) -> Unit = {},
    private val connectTimeoutMs: Int = 10_000,
) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    /** Binds an ephemeral loopback port and starts accepting. Returns the port. */
    fun start(): Int {
        val s = ServerSocket(0, 128, LOOPBACK_V4)
        val p = Executors.newCachedThreadPool { r -> Thread(r, "warden-socks").apply { isDaemon = true } }
        server = s
        pool = p
        p.execute {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: IOException) { break }
                p.execute { serve(client) }
            }
        }
        return s.localPort
    }

    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        pool?.shutdownNow()
        pool = null
    }

    private fun serve(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            // Greeting: VER, NMETHODS, METHODS. Only "no authentication" is offered.
            if (input.readUnsignedByte() != VER) return client.close()
            val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
            if (methods.none { it.toInt() == 0 }) {
                output.write(byteArrayOf(VER.toByte(), 0xFF.toByte()))
                return client.close()
            }
            output.write(byteArrayOf(VER.toByte(), 0))

            // Request: VER, CMD, RSV, then the destination address.
            if (input.readUnsignedByte() != VER) return client.close()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte()
            val target = readAddress(input) ?: return reply(output, REP_ADDRESS_UNSUPPORTED).also { client.close() }
            when (cmd) {
                CMD_CONNECT -> connect(client, input, output, target)
                CMD_UDP_ASSOCIATE -> associate(client, input, output)
                else -> { reply(output, REP_COMMAND_UNSUPPORTED); client.close() }
            }
        } catch (e: IOException) {
            closeQuietly(client)
        }
    }

    private fun connect(client: Socket, input: InputStream, output: OutputStream, target: InetSocketAddress) {
        val remote = Socket()
        try {
            protect(remote)
            remote.tcpNoDelay = true
            remote.connect(target, connectTimeoutMs)
        } catch (e: IOException) {
            reply(output, if (e is SocketException) REP_HOST_UNREACHABLE else REP_FAILURE)
            closeQuietly(remote); closeQuietly(client)
            return
        }
        reply(output, REP_SUCCEEDED)
        // One thread per direction. A half-close (EOF) is passed on as a half-close, so
        // request/response protocols that shut down their write side still complete;
        // whichever direction finishes second closes both sockets.
        val finished = AtomicInteger()
        val p = pool ?: return closeQuietly(remote, client)
        p.execute { pipe(remote.getInputStream(), output, client, remote, client, finished) }
        pipe(input, remote.getOutputStream(), remote, client, remote, finished)
    }

    private fun pipe(
        from: InputStream, to: OutputStream, toSocket: Socket,
        a: Socket, b: Socket, finished: AtomicInteger,
    ) {
        val buf = ByteArray(BUFFER)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
            }
            toSocket.shutdownOutput()
        } catch (e: IOException) {
            closeQuietly(a, b) // a reset on either side ends both
            return
        }
        if (finished.incrementAndGet() == 2) closeQuietly(a, b)
    }

    /**
     * UDP ASSOCIATE: one loopback socket faces the stack (datagrams carry a SOCKS
     * header naming their destination), one protected socket faces the network.
     * The association lives as long as the control TCP connection.
     */
    private fun associate(client: Socket, input: InputStream, output: OutputStream) {
        val local = DatagramSocket(InetSocketAddress(LOOPBACK_V4, 0))
        val outbound = DatagramSocket()
        try {
            protectUdp(outbound)
        } catch (e: IOException) {
            reply(output, REP_FAILURE); closeQuietly(client); local.close(); outbound.close()
            return
        }
        reply(output, REP_SUCCEEDED, local.localAddress, local.localPort)
        // Replies go to wherever the stack last sent from.
        val peer = AtomicReference<InetSocketAddress?>()

        pool?.execute {
            val buf = ByteArray(UDP_BUFFER)
            val packet = DatagramPacket(buf, buf.size)
            try {
                while (true) {
                    packet.setData(buf)
                    outbound.receive(packet)
                    val to = peer.get() ?: continue
                    val header = udpHeader(packet.address, packet.port)
                    val out = ByteArray(header.size + packet.length)
                    header.copyInto(out)
                    buf.copyInto(out, header.size, 0, packet.length)
                    local.send(DatagramPacket(out, out.size, to))
                }
            } catch (_: IOException) {
            }
        }
        pool?.execute {
            val buf = ByteArray(UDP_BUFFER)
            val packet = DatagramPacket(buf, buf.size)
            try {
                while (true) {
                    packet.setData(buf)
                    local.receive(packet)
                    peer.set(packet.socketAddress as InetSocketAddress)
                    val parsed = parseUdpRequest(buf, packet.length) ?: continue
                    val (dst, offset) = parsed
                    outbound.send(DatagramPacket(buf, offset, packet.length - offset, dst))
                }
            } catch (_: IOException) {
            }
        }
        try {
            // Nothing more is sent on the control connection; its EOF ends the association.
            while (input.read() >= 0) Unit
        } catch (_: IOException) {
        } finally {
            local.close(); outbound.close(); closeQuietly(client)
        }
    }

    private fun closeQuietly(vararg sockets: Socket) {
        for (s in sockets) try { s.close() } catch (_: IOException) {}
    }

    companion object {
        /**
         * The stack is configured to dial 127.0.0.1. Not InetAddress.getLoopbackAddress():
         * on Android that is ::1, and a socket bound there never sees the stack's IPv4 dial.
         */
        val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        private const val VER = 5
        private const val CMD_CONNECT = 1
        private const val CMD_UDP_ASSOCIATE = 3
        private const val ATYP_V4 = 1
        private const val ATYP_DOMAIN = 3
        private const val ATYP_V6 = 4

        private const val REP_SUCCEEDED = 0
        private const val REP_FAILURE = 1
        private const val REP_HOST_UNREACHABLE = 4
        private const val REP_COMMAND_UNSUPPORTED = 7
        private const val REP_ADDRESS_UNSUPPORTED = 8

        private const val BUFFER = 16 * 1024
        private const val UDP_BUFFER = 65_535

        private fun readAddress(input: DataInputStream): InetSocketAddress? {
            val addr = when (input.readUnsignedByte()) {
                ATYP_V4 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully))
                ATYP_V6 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully))
                // The stack only ever sends addresses. A name would have to be resolved here,
                // outside Warden's DNS filter, so it is refused rather than honoured.
                ATYP_DOMAIN -> {
                    input.skipBytes(input.readUnsignedByte() + 2)
                    return null
                }
                else -> return null
            }
            return InetSocketAddress(addr, input.readUnsignedShort())
        }

        private fun reply(out: OutputStream, rep: Int, bound: InetAddress? = null, port: Int = 0) {
            val addr = bound?.address ?: ByteArray(4)
            val atyp = if (addr.size == 16) ATYP_V6 else ATYP_V4
            out.write(byteArrayOf(VER.toByte(), rep.toByte(), 0, atyp.toByte()) + addr +
                byteArrayOf((port ushr 8).toByte(), port.toByte()))
            out.flush()
        }

        /** RSV(2) FRAG(1) ATYP ADDR PORT — fragmented datagrams are dropped, as RFC 1928 allows. */
        internal fun parseUdpRequest(b: ByteArray, len: Int): Pair<InetSocketAddress, Int>? {
            if (len < 4 || b[2].toInt() != 0) return null
            val addrLen = when (b[3].toInt()) {
                ATYP_V4 -> 4
                ATYP_V6 -> 16
                else -> return null
            }
            val portAt = 4 + addrLen
            if (len < portAt + 2) return null
            val addr = InetAddress.getByAddress(b.copyOfRange(4, portAt))
            val port = ((b[portAt].toInt() and 0xFF) shl 8) or (b[portAt + 1].toInt() and 0xFF)
            return InetSocketAddress(addr, port) to portAt + 2
        }

        internal fun udpHeader(from: InetAddress, port: Int): ByteArray {
            val addr = from.address
            val atyp = if (addr.size == 16) ATYP_V6 else ATYP_V4
            return byteArrayOf(0, 0, 0, atyp.toByte()) + addr + byteArrayOf((port ushr 8).toByte(), port.toByte())
        }
    }
}
