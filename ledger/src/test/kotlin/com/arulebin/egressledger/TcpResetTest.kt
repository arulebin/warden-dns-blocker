package com.arulebin.egressledger

import com.arulebin.egressledger.capture.Packets
import com.arulebin.egressledger.capture.TcpReset
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TcpResetTest {
    private val hello = Fixtures.clientHello

    // ---- the cheap per-packet filter ----

    @Test fun `pre-filter passes a ClientHello to 443 and nothing else`() {
        assertTrue(Packets.isTlsHandshakeTo443(Fixtures.ipv4Tcp("10.0.0.1", 5000, "1.2.3.4", 443, hello)))
        assertTrue(Packets.isTlsHandshakeTo443(Fixtures.ipv6Tcp("fd00::1", 5000, "2001:db8::1", 443, hello)))
        assertFalse(Packets.isTlsHandshakeTo443(Fixtures.ipv4Tcp("10.0.0.1", 5000, "1.2.3.4", 80, hello)))
        assertFalse(Packets.isTlsHandshakeTo443(Fixtures.ipv4Tcp("10.0.0.1", 5000, "1.2.3.4", 443, ByteArray(0))))
        assertFalse(Packets.isTlsHandshakeTo443(Fixtures.ipv4Tcp("10.0.0.1", 5000, "1.2.3.4", 443, byteArrayOf(23, 3, 3))))
        assertFalse(Packets.isTlsHandshakeTo443(Fixtures.ipv4Udp(443)))
    }

    @Test fun `reads the UDP destination port and ignores TCP`() {
        assertEquals(443, Packets.udpDstPort(Fixtures.ipv4Udp(443)))
        assertEquals(-1, Packets.udpDstPort(Fixtures.ipv4Tcp("10.0.0.1", 5000, "1.2.3.4", 443, hello)))
        assertEquals(-1, Packets.udpDstPort(ByteArray(3)))
    }

    // ---- resets ----

    @Test fun `reset to the client mirrors the flow and acknowledges the withheld hello`() {
        val seg = assertNotNull(Packets.parseTcp(
            Fixtures.ipv4Tcp("10.215.173.1", 51234, "162.159.140.245", 443, hello, seq = 0xFFFFFF00, ack = 777),
        ))
        val rst = assertNotNull(Packets.parseTcp(TcpReset.toClient(seg)))
        assertEquals(InetAddress.getByName("162.159.140.245"), rst.flow.src)
        assertEquals(443, rst.flow.srcPort)
        assertEquals(51234, rst.flow.dstPort)
        assertEquals(Packets.TCP_RST or Packets.TCP_ACK, rst.flags)
        assertEquals(777, rst.seq)
        assertEquals((0xFFFFFF00 + hello.size) and 0xFFFFFFFF, rst.ack) // wraps past 2^32
        assertValidChecksums(TcpReset.toClient(seg), ipv6 = false)
    }

    @Test fun `reset to the stack keeps the flow at the hello's sequence number`() {
        val seg = assertNotNull(Packets.parseTcp(
            Fixtures.ipv6Tcp("fd00:2:fd00::1", 40001, "2001:db8::5", 443, hello, seq = 1000, ack = 2000),
        ))
        val bytes = TcpReset.toServer(seg)
        val rst = assertNotNull(Packets.parseTcp(bytes))
        assertEquals(seg.flow, rst.flow)
        assertEquals(Packets.TCP_RST, rst.flags)
        assertEquals(1000, rst.seq)
        assertValidChecksums(bytes, ipv6 = true)
    }

    /** A correct checksum makes the one's-complement sum over the covered bytes come out as 0xFFFF. */
    private fun assertValidChecksums(p: ByteArray, ipv6: Boolean) {
        val ip = if (ipv6) 40 else 20
        if (!ipv6) assertEquals(0xFFFF, fold(sum(p, 0, 20)), "IPv4 header checksum")
        val src = if (ipv6) p.copyOfRange(8, 24) else p.copyOfRange(12, 16)
        val dst = if (ipv6) p.copyOfRange(24, 40) else p.copyOfRange(16, 20)
        val tcpLen = p.size - ip
        val total = sum(src, 0, src.size) + sum(dst, 0, dst.size) + 6 + tcpLen + sum(p, ip, tcpLen)
        assertEquals(0xFFFF, fold(total), "TCP checksum")
    }

    private fun sum(b: ByteArray, off: Int, len: Int): Long {
        var s = 0L
        for (i in off until off + len step 2) s += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        return s
    }

    private fun fold(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return s.toInt()
    }
}
