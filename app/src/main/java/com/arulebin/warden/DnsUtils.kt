package com.arulebin.warden

/**
 * Minimal IPv4 + IPv6 + UDP + DNS parsing/building — enough to filter DNS over
 * both address families. A query arrives as a raw IP packet; we pull the
 * hostname out of the DNS message and, to answer, build a fresh packet back.
 */
object DnsUtils {

    class DnsQuery(
        val ipv6: Boolean,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dnsMessage: ByteArray,
        val domain: String,
    )

    private const val PROTO_UDP = 17

    fun parseDnsQuery(packet: ByteArray, length: Int): DnsQuery? {
        if (length < 1) return null
        return when ((packet[0].toInt() and 0xF0) ushr 4) {
            4 -> parseV4(packet, length)
            6 -> parseV6(packet, length)
            else -> null
        }
    }

    private fun parseV4(p: ByteArray, len: Int): DnsQuery? {
        if (len < 28) return null
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((p[9].toInt() and 0xFF) != PROTO_UDP) return null
        val u = ihl
        if (u16(p, u + 2) != 53) return null
        val dnsOff = u + 8
        if (len < dnsOff + 12) return null
        val dns = p.copyOfRange(dnsOff, len)
        val domain = extractDomain(dns) ?: return null
        return DnsQuery(false, p.copyOfRange(12, 16), p.copyOfRange(16, 20), u16(p, u), u16(p, u + 2), dns, domain)
    }

    private fun parseV6(p: ByteArray, len: Int): DnsQuery? {
        if (len < 48) return null // 40 (IPv6) + 8 (UDP)
        if ((p[6].toInt() and 0xFF) != PROTO_UDP) return null // no extension headers handled
        val u = 40
        if (u16(p, u + 2) != 53) return null
        val dnsOff = u + 8
        if (len < dnsOff + 12) return null
        val dns = p.copyOfRange(dnsOff, len)
        val domain = extractDomain(dns) ?: return null
        return DnsQuery(true, p.copyOfRange(8, 24), p.copyOfRange(24, 40), u16(p, u), u16(p, u + 2), dns, domain)
    }

    /** First question's QNAME — DNS encodes it as length-prefixed labels. */
    private fun extractDomain(dns: ByteArray): String? {
        var pos = 12
        val sb = StringBuilder()
        while (pos < dns.size) {
            val labelLen = dns[pos].toInt() and 0xFF
            if (labelLen == 0) break
            if (labelLen and 0xC0 != 0) return null // compression pointer — not in questions
            pos++
            if (pos + labelLen > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until labelLen) sb.append((dns[pos + i].toInt() and 0xFF).toChar())
            pos += labelLen
        }
        return if (sb.isEmpty()) null else sb.toString().lowercase()
    }

    fun buildNxDomainResponse(query: ByteArray): ByteArray {
        val r = query.copyOf()
        r[2] = (0x80 or (query[2].toInt() and 0x79)).toByte() // QR + keep Opcode/RD
        r[3] = 0x83.toByte()                                  // RA + RCODE 3 (NXDOMAIN)
        r[6] = 0; r[7] = 0; r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0
        return r
    }

    /** Build the reply packet matching the query's address family. */
    fun buildResponsePacket(q: DnsQuery, dnsPayload: ByteArray): ByteArray =
        if (q.ipv6) buildV6(q.dstIp, q.srcIp, 53, q.srcPort, dnsPayload)
        else buildV4(q.dstIp, q.srcIp, 53, q.srcPort, dnsPayload)

    private fun buildV4(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val ipLen = 20
        val udpLen = 8 + payload.size
        val total = ipLen + udpLen
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total ushr 8).toByte(); p[3] = total.toByte()
        p[8] = 64
        p[9] = PROTO_UDP.toByte()
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        val ipCsum = checksum16(p, 0, ipLen)
        p[10] = (ipCsum ushr 8).toByte(); p[11] = ipCsum.toByte()
        writeUdpHeader(p, ipLen, srcPort, dstPort, udpLen)
        System.arraycopy(payload, 0, p, ipLen + 8, payload.size)
        // IPv4 UDP checksum is optional; leave 0.
        return p
    }

    private fun buildV6(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 40 + udpLen
        val p = ByteArray(total)
        p[0] = 0x60 // version 6
        p[4] = (udpLen ushr 8).toByte(); p[5] = udpLen.toByte() // payload length
        p[6] = PROTO_UDP.toByte() // next header
        p[7] = 64 // hop limit
        System.arraycopy(srcIp, 0, p, 8, 16)
        System.arraycopy(dstIp, 0, p, 24, 16)
        writeUdpHeader(p, 40, srcPort, dstPort, udpLen)
        System.arraycopy(payload, 0, p, 48, payload.size)
        // IPv6 UDP checksum is mandatory.
        val csum = udpChecksumV6(srcIp, dstIp, p, 40, udpLen)
        p[46] = (csum ushr 8).toByte(); p[47] = csum.toByte()
        return p
    }

    private fun writeUdpHeader(p: ByteArray, off: Int, srcPort: Int, dstPort: Int, udpLen: Int) {
        p[off] = (srcPort ushr 8).toByte(); p[off + 1] = srcPort.toByte()
        p[off + 2] = (dstPort ushr 8).toByte(); p[off + 3] = dstPort.toByte()
        p[off + 4] = (udpLen ushr 8).toByte(); p[off + 5] = udpLen.toByte()
        // checksum bytes (off+6, off+7) left 0 here
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun checksum16(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        var remaining = length
        while (remaining > 1) { sum += u16(data, i).toLong(); i += 2; remaining -= 2 }
        if (remaining > 0) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun udpChecksumV6(src: ByteArray, dst: ByteArray, packet: ByteArray, udpOff: Int, udpLen: Int): Int {
        var sum = 0L
        var i = 0
        while (i < 16) { sum += (((src[i].toInt() and 0xFF) shl 8) or (src[i + 1].toInt() and 0xFF)).toLong(); i += 2 }
        i = 0
        while (i < 16) { sum += (((dst[i].toInt() and 0xFF) shl 8) or (dst[i + 1].toInt() and 0xFF)).toLong(); i += 2 }
        sum += ((udpLen ushr 16) and 0xFFFF).toLong()
        sum += (udpLen and 0xFFFF).toLong()
        sum += PROTO_UDP.toLong()
        var j = udpOff
        var remaining = udpLen
        while (remaining > 1) { sum += u16(packet, j).toLong(); j += 2; remaining -= 2 }
        if (remaining > 0) sum += ((packet[j].toInt() and 0xFF) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        val res = (sum.inv() and 0xFFFF).toInt()
        return if (res == 0) 0xFFFF else res
    }
}
