package com.arulebin.warden

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainSetTest {
    private fun setOf(vararg domains: String) =
        DomainSet.Builder(2).apply { domains.forEach(::add) }.build()

    @Test fun `matches the domain and its subdomains only`() {
        val set = setOf("example.com")
        assertTrue(set.containsHost("example.com"))
        assertTrue(set.containsHost("cdn.static.example.com"))
        assertFalse(set.containsHost("notexample.com"))
        assertFalse(set.containsHost("example.com.evil.net"))
    }

    @Test fun `deduplicates while growing past its initial capacity`() {
        val set = setOf("a.com", "b.com", "a.com", "c.com", "b.com")
        assertEquals(3, set.size)
    }

    @Test fun `round-trips through the WDN1 file format`() {
        val file = File.createTempFile("domainset", ".bin").apply { deleteOnExit() }
        setOf("a.com", "b.org").writeTo(file)
        val back = assertNotNull(DomainSet.readFrom(file))
        assertEquals(2, back.size)
        assertTrue(back.containsHost("x.b.org"))
    }

    @Test fun `refuses a file without the magic header`() {
        val file = File.createTempFile("domainset", ".bin").apply { deleteOnExit() }
        file.writeBytes(ByteArray(16))
        assertNull(DomainSet.readFrom(file))
    }
}
