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

/** One TCP segment read off the TUN interface: its flow, and where its payload sits in the packet. */
class TcpSegment(
    val flow: FlowKey,
    val packet: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int,
)

/**
 * Minimal IPv4/IPv6 + TCP header parsing — the TCP-side counterpart of Warden's
 * DnsUtils, which only understands UDP port 53.
 */
object Packets {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    fun parseTcp(packet: ByteArray, length: Int = packet.size): TcpSegment? {
        if (length < 1) return null
        return when ((packet[0].toInt() and 0xF0) ushr 4) {
            4 -> parseV4(packet, length)
            6 -> parseV6(packet, length)
            else -> null
        }
    }

    private fun parseV4(p: ByteArray, len: Int): TcpSegment? {
        if (len < 20) return null
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (ihl < 20 || p[9].toInt() != PROTO_TCP) return null
        // A non-first fragment carries no TCP header; a ClientHello is never fragmented at IP level in practice.
        if ((u16(p, 6) and 0x1FFF) != 0) return null
        val end = minOf(len, u16(p, 2))
        val src = InetAddress.getByAddress(p.copyOfRange(12, 16))
        val dst = InetAddress.getByAddress(p.copyOfRange(16, 20))
        return parseTcpHeader(p, ihl, end, src, dst)
    }

    private fun parseV6(p: ByteArray, len: Int): TcpSegment? {
        if (len < 40) return null
        // Extension headers are skipped deliberately: they are rare on TCP from an app and
        // an unparsed packet is simply not measured (the count stays a lower bound).
        if (p[6].toInt() != PROTO_TCP) return null
        val end = minOf(len, 40 + u16(p, 4))
        val src = InetAddress.getByAddress(p.copyOfRange(8, 24))
        val dst = InetAddress.getByAddress(p.copyOfRange(24, 40))
        return parseTcpHeader(p, 40, end, src, dst)
    }

    private fun parseTcpHeader(p: ByteArray, off: Int, end: Int, src: InetAddress, dst: InetAddress): TcpSegment? {
        if (end < off + 20) return null
        val dataOffset = ((p[off + 12].toInt() and 0xF0) ushr 4) * 4
        if (dataOffset < 20 || off + dataOffset > end) return null
        val flow = FlowKey(PROTO_TCP, src, u16(p, off), dst, u16(p, off + 2))
        return TcpSegment(flow, p, off + dataOffset, end - off - dataOffset)
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}
