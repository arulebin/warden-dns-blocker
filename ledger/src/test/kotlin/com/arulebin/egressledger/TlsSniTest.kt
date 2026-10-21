package com.arulebin.egressledger

import com.arulebin.egressledger.capture.Packets
import com.arulebin.egressledger.capture.TlsSni
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TlsSniTest {
    private val hello = Fixtures.clientHello

    @Test fun `reads the SNI from a real OpenSSL ClientHello`() {
        assertEquals("api.openai.com", TlsSni.extract(hello))
    }

    @Test fun `reads the SNI when only the first TCP segment of the hello arrived`() {
        // Post-quantum key shares push hellos past one segment; the SNI must survive the cut.
        val sniEnd = hello.indexOfSubsequence("api.openai.com".toByteArray()) + "api.openai.com".length
        assertEquals("api.openai.com", TlsSni.extract(hello.copyOf(sniEnd + 8)))
    }

    @Test fun `returns null when the hello is cut before the SNI`() {
        assertNull(TlsSni.extract(hello.copyOf(60)))
    }

    @Test fun `returns null for plain HTTP`() {
        assertNull(TlsSni.extract("GET / HTTP/1.1\r\nHost: api.openai.com\r\n\r\n".toByteArray()))
    }

    @Test fun `returns null for a TLS record that is not a handshake`() {
        val appData = hello.copyOf().also { it[0] = 23 }
        assertNull(TlsSni.extract(appData))
    }

    @Test fun `never throws on arbitrary bytes`() {
        val rnd = java.util.Random(7)
        repeat(5_000) {
            val junk = ByteArray(rnd.nextInt(600)).also(rnd::nextBytes)
            if (junk.isNotEmpty()) junk[0] = 22
            TlsSni.extract(junk) // must return, never index out of range
        }
    }

    @Test fun `parses the 5-tuple and payload offset of an IPv4 TCP packet`() {
        val pkt = Fixtures.ipv4Tcp("10.215.173.1", 51234, "162.159.140.245", 443, hello)
        val seg = assertNotNull(Packets.parseTcp(pkt))
        assertEquals(51234, seg.flow.srcPort)
        assertEquals(443, seg.flow.dstPort)
        assertEquals(40, seg.payloadOffset)
        assertEquals(hello.size, seg.payloadLength)
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int =
        (0..size - needle.size).first { i -> needle.indices.all { this[i + it] == needle[it] } }
}
