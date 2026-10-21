package com.arulebin.egressledger.capture

/**
 * Builds the two TCP resets that end a blocked connection at its ClientHello.
 *
 * The relay terminates TCP itself (the app's handshake was answered by the tunnel's
 * stack, not the real server), so dropping the hello is not enough: the app would
 * retransmit until it timed out, and the stack would hold its half open. Instead the
 * hello is withheld and each side is told the other reset — the app sees an
 * immediate "connection reset", which apps already treat as a network failure.
 */
object TcpReset {
    /** RST|ACK to the app, as if from the server: seq = what the app expects next, ack = past the withheld hello. */
    fun toClient(seg: TcpSegment): ByteArray =
        build(
            seg.ipv6,
            src = seg.flow.dst.address, srcPort = seg.flow.dstPort,
            dst = seg.flow.src.address, dstPort = seg.flow.srcPort,
            seq = seg.ack, ack = (seg.seq + seg.payloadLength) and 0xFFFFFFFFL,
            flags = Packets.TCP_RST or Packets.TCP_ACK,
        )

    /** RST to the stack, as if from the app, at the hello's own sequence number (exactly what the stack expects next). */
    fun toServer(seg: TcpSegment): ByteArray =
        build(
            seg.ipv6,
            src = seg.flow.src.address, srcPort = seg.flow.srcPort,
            dst = seg.flow.dst.address, dstPort = seg.flow.dstPort,
            seq = seg.seq, ack = 0, flags = Packets.TCP_RST,
        )

    private fun build(
        ipv6: Boolean, src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int,
        seq: Long, ack: Long, flags: Int,
    ): ByteArray {
        val ip = if (ipv6) 40 else 20
        val p = ByteArray(ip + TCP_LEN)
        if (ipv6) {
            p[0] = 0x60
            put16(p, 4, TCP_LEN)
            p[6] = Packets.PROTO_TCP.toByte()
            p[7] = 64
            src.copyInto(p, 8)
            dst.copyInto(p, 24)
        } else {
            p[0] = 0x45
            put16(p, 2, p.size)
            p[8] = 64
            p[9] = Packets.PROTO_TCP.toByte()
            src.copyInto(p, 12)
            dst.copyInto(p, 16)
            put16(p, 10, Checksum.fold(Checksum.sum(p, 0, 20)))
        }
        put16(p, ip, srcPort)
        put16(p, ip + 2, dstPort)
        put32(p, ip + 4, seq)
        put32(p, ip + 8, ack)
        p[ip + 12] = ((TCP_LEN / 4) shl 4).toByte()
        p[ip + 13] = flags.toByte()
        // Window 0 and no options: a reset carries nothing.
        val pseudo = Checksum.sum(src, 0, src.size) + Checksum.sum(dst, 0, dst.size) +
            Packets.PROTO_TCP + TCP_LEN
        put16(p, ip + 16, Checksum.fold(pseudo + Checksum.sum(p, ip, TCP_LEN)))
        return p
    }

    private const val TCP_LEN = 20

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, off: Int, v: Long) {
        put16(b, off, (v ushr 16).toInt() and 0xFFFF); put16(b, off + 2, v.toInt() and 0xFFFF)
    }
}

/** RFC 1071 internet checksum, split so a pseudo-header can be summed in. */
internal object Checksum {
    fun sum(b: ByteArray, off: Int, len: Int): Long {
        var s = 0L
        var i = off
        while (i + 1 < off + len) { s += Packets.u16(b, i); i += 2 }
        if (i < off + len) s += (b[i].toInt() and 0xFF) shl 8
        return s
    }

    fun fold(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }
}
