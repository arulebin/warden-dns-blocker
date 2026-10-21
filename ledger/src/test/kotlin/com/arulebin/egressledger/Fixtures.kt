package com.arulebin.egressledger

import java.net.InetAddress

object Fixtures {
    /**
     * A genuine ClientHello for api.openai.com, produced offline by OpenSSL 3.0
     * (Python ssl + MemoryBIO — no network). 517 bytes, record header included.
     */
    val clientHello: ByteArray by lazy {
        Fixtures::class.java.getResourceAsStream("/clienthello_api_openai_com.bin")!!.readBytes()
    }

    /** Wraps [payload] in an IPv4 + TCP header, as it would come off the TUN interface. */
    fun ipv4Tcp(
        src: String, srcPort: Int, dst: String, dstPort: Int, payload: ByteArray,
        seq: Long = 0, ack: Long = 0,
    ): ByteArray {
        val p = ByteArray(40 + payload.size)
        p[0] = 0x45                                   // v4, IHL 5
        putU16(p, 2, p.size)                          // total length
        p[8] = 64                                     // TTL
        p[9] = 6                                      // TCP
        InetAddress.getByName(src).address.copyInto(p, 12)
        InetAddress.getByName(dst).address.copyInto(p, 16)
        tcpHeader(p, 20, srcPort, dstPort, seq, ack)
        payload.copyInto(p, 40)
        return p
    }

    /** Same as [ipv4Tcp] over IPv6 (no extension headers). */
    fun ipv6Tcp(
        src: String, srcPort: Int, dst: String, dstPort: Int, payload: ByteArray,
        seq: Long = 0, ack: Long = 0,
    ): ByteArray {
        val p = ByteArray(60 + payload.size)
        p[0] = 0x60                                   // v6
        putU16(p, 4, 20 + payload.size)               // payload length
        p[6] = 6                                      // next header: TCP
        p[7] = 64                                     // hop limit
        InetAddress.getByName(src).address.copyInto(p, 8)
        InetAddress.getByName(dst).address.copyInto(p, 24)
        tcpHeader(p, 40, srcPort, dstPort, seq, ack)
        payload.copyInto(p, 60)
        return p
    }

    /** A bare IPv4 UDP datagram (e.g. a QUIC Initial) to [dstPort]. */
    fun ipv4Udp(dstPort: Int, payloadSize: Int = 32): ByteArray {
        val p = ByteArray(28 + payloadSize)
        p[0] = 0x45
        putU16(p, 2, p.size)
        p[9] = 17
        putU16(p, 22, dstPort)
        putU16(p, 24, 8 + payloadSize)
        return p
    }

    private fun tcpHeader(p: ByteArray, off: Int, srcPort: Int, dstPort: Int, seq: Long, ack: Long) {
        putU16(p, off, srcPort)
        putU16(p, off + 2, dstPort)
        putU16(p, off + 4, (seq ushr 16).toInt()); putU16(p, off + 6, seq.toInt())
        putU16(p, off + 8, (ack ushr 16).toInt()); putU16(p, off + 10, ack.toInt())
        p[off + 12] = 0x50                            // data offset 5 words
        p[off + 13] = 0x18                            // PSH|ACK
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte()
    }
}
