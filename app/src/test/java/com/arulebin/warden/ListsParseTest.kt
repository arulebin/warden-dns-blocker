package com.arulebin.warden

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListsParseTest {

    @Test fun `reads every supported list format`() {
        assertEquals("ads.example.com", Lists.parseLine("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", Lists.parseLine("127.0.0.1\tads.example.com  # tracker"))
        assertEquals("ads.example.com", Lists.parseLine("ads.example.com"))
        assertEquals("example.com", Lists.parseLine("*.example.com"))
        assertEquals("example.com", Lists.parseLine("||example.com^"))
        assertEquals("example.com", Lists.parseLine("Example.COM."))
    }

    @Test fun `skips comments, headers and loopback entries`() {
        assertNull(Lists.parseLine("# a comment"))
        assertNull(Lists.parseLine("! adblock comment"))
        assertNull(Lists.parseLine("[Adblock Plus 2.0]"))
        assertNull(Lists.parseLine("   "))
        assertNull(Lists.parseLine("127.0.0.1 localhost"))
        assertNull(Lists.parseLine("0.0.0.0 0.0.0.0"))
    }

    @Test fun `rejects bare labels and malformed hosts`() {
        assertNull(Lists.parseLine("intranet"))
        assertNull(Lists.parseLine("0.0.0.0 bad/host.com"))
    }
}
