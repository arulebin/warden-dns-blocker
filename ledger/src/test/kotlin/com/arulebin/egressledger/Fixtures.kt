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
    fun ipv4Tcp(src: String, srcPort: Int, dst: String, dstPort: Int, payload: ByteArray): ByteArray {
        val p = ByteArray(40 + payload.size)
        p[0] = 0x45                                   // v4, IHL 5
        putU16(p, 2, p.size)                          // total length
        p[8] = 64                                     // TTL
        p[9] = 6                                      // TCP
        InetAddress.getByName(src).address.copyInto(p, 12)
        InetAddress.getByName(dst).address.copyInto(p, 16)
        putU16(p, 20, srcPort)
        putU16(p, 22, dstPort)
        p[32] = 0x50                                  // data offset 5 words
        p[33] = 0x18                                  // PSH|ACK
        payload.copyInto(p, 40)
        return p
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte()
    }
}
