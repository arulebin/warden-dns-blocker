package com.arulebin.egressledger.capture

import java.net.InetAddress

/**
 * The 5-tuple that identifies one connection. It is exactly what
 * ConnectivityManager.getConnectionOwnerUid() takes, so a flow parsed off the
 * tunnel can be handed straight to the attributor.
 */
data class FlowKey(
    val protocol: Int,
    val src: InetAddress,
    val srcPort: Int,
    val dst: InetAddress,
    val dstPort: Int,
)

/** One TCP segment read off the TUN interface: its flow, sequence state, and where its payload sits in the packet. */
class TcpSegment(
    val flow: FlowKey,
    val packet: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int,
    val ipv6: Boolean,
    val seq: Long,
    val ack: Long,
    val flags: Int,
)

/**
 * Minimal IPv4/IPv6 + TCP header parsing — the TCP-side counterpart of Warden's
 * DnsUtils, which only understands UDP port 53.
 */
object Packets {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    private const val TLS_HANDSHAKE = 22

    fun parseTcp(packet: ByteArray, length: Int = packet.size): TcpSegment? {
        if (length < 1) return null
        return when ((packet[0].toInt() and 0xF0) ushr 4) {
            4 -> parseV4(packet, length)
            6 -> parseV6(packet, length)
            else -> null
        }
    }

    /**
     * Cheap pre-filter for every packet on the tunnel: TCP to port 443 whose payload
     * starts a TLS handshake record. Allocates nothing, so the full parse (two
     * InetAddress objects and a FlowKey) only runs on the few packets that can carry
     * a ClientHello — not on bulk data.
     */
    fun isTlsHandshakeTo443(p: ByteArray, len: Int = p.size): Boolean {
        val tcp = transportOffset(p, len, PROTO_TCP)
        if (tcp < 0 || len < tcp + 20 || u16(p, tcp + 2) != 443) return false
        val payload = tcp + ((p[tcp + 12].toInt() and 0xF0) ushr 4) * 4
        return payload < ipEnd(p, len) && p[payload].toInt() == TLS_HANDSHAKE
    }

    /** Destination port of a UDP packet, or -1 for anything else. Used to hold QUIC back so apps fall back to TCP. */
    fun udpDstPort(p: ByteArray, len: Int = p.size): Int {
        val udp = transportOffset(p, len, PROTO_UDP)
        return if (udp < 0 || len < udp + 8) -1 else u16(p, udp + 2)
    }

    /** Offset of the transport header when the packet carries [proto] (first fragment, no v6 extension headers), else -1. */
    private fun transportOffset(p: ByteArray, len: Int, proto: Int): Int {
        if (len < 1) return -1
        return when ((p[0].toInt() and 0xF0) ushr 4) {
            4 -> {
                if (len < 20) return -1
                val ihl = (p[0].toInt() and 0x0F) * 4
                if (ihl < 20 || p[9].toInt() != proto || (u16(p, 6) and 0x1FFF) != 0) -1 else ihl
            }
            6 -> if (len < 40 || p[6].toInt() != proto) -1 else 40
            else -> -1
        }
    }

    private fun ipEnd(p: ByteArray, len: Int): Int =
        if ((p[0].toInt() and 0xF0) ushr 4 == 4) minOf(len, u16(p, 2)) else minOf(len, 40 + u16(p, 4))

    private fun parseV4(p: ByteArray, len: Int): TcpSegment? {
        if (len < 20) return null
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (ihl < 20 || p[9].toInt() != PROTO_TCP) return null
        // A non-first fragment carries no TCP header; a ClientHello is never fragmented at IP level in practice.
        if ((u16(p, 6) and 0x1FFF) != 0) return null
        val end = minOf(len, u16(p, 2))
        val src = InetAddress.getByAddress(p.copyOfRange(12, 16))
        val dst = InetAddress.getByAddress(p.copyOfRange(16, 20))
        return parseTcpHeader(p, ihl, end, src, dst, ipv6 = false)
    }

    private fun parseV6(p: ByteArray, len: Int): TcpSegment? {
        if (len < 40) return null
        // Extension headers are skipped deliberately: they are rare on TCP from an app and
        // an unparsed packet is simply not measured (the count stays a lower bound).
        if (p[6].toInt() != PROTO_TCP) return null
        val end = minOf(len, 40 + u16(p, 4))
        val src = InetAddress.getByAddress(p.copyOfRange(8, 24))
        val dst = InetAddress.getByAddress(p.copyOfRange(24, 40))
        return parseTcpHeader(p, 40, end, src, dst, ipv6 = true)
    }

    private fun parseTcpHeader(
        p: ByteArray, off: Int, end: Int, src: InetAddress, dst: InetAddress, ipv6: Boolean,
    ): TcpSegment? {
        if (end < off + 20) return null
        val dataOffset = ((p[off + 12].toInt() and 0xF0) ushr 4) * 4
        if (dataOffset < 20 || off + dataOffset > end) return null
        val flow = FlowKey(PROTO_TCP, src, u16(p, off), dst, u16(p, off + 2))
        return TcpSegment(
            flow, p, off + dataOffset, end - off - dataOffset,
            ipv6, u32(p, off + 4), u32(p, off + 8), p[off + 13].toInt() and 0x3F,
        )
    }

    internal fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Long =
        (u16(b, off).toLong() shl 16) or u16(b, off + 2).toLong()
}
