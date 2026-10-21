package com.arulebin.warden

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rules without init(): no SharedPreferences, so the user lists live only in memory. */
class RulesTest {
    @AfterTest fun clear() {
        Rules.userBlocked.toList().forEach(Rules::removeBlocked)
        Rules.userAllowed.toList().forEach(Rules::removeAllowed)
    }

    @Test fun `a user block covers subdomains`() {
        Rules.addBlocked("https://www.Tracker.example/path")
        assertTrue(Rules.isBlocked("tracker.example"))
        assertTrue(Rules.isBlocked("pixel.tracker.example."))
        assertFalse(Rules.isBlocked("example"))
    }

    @Test fun `the allowlist beats the blocklist`() {
        Rules.addBlocked("example.com")
        Rules.addAllowed("good.example.com")
        assertTrue(Rules.isBlocked("bad.example.com"))
        assertFalse(Rules.isBlocked("good.example.com"))
        assertFalse(Rules.isBlocked("cdn.good.example.com"))
    }
}
