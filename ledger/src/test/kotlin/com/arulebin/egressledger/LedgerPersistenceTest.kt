package com.arulebin.egressledger

import com.arulebin.egressledger.classify.Provider
import com.arulebin.egressledger.ledger.AppIdentity
import com.arulebin.egressledger.ledger.Attributor
import com.arulebin.egressledger.ledger.EgressInspector
import com.arulebin.egressledger.ledger.EgressLedger
import com.arulebin.egressledger.ledger.EgressPolicy
import com.arulebin.egressledger.ledger.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LedgerPersistenceTest {
    private val app = AppIdentity(10_200, listOf("com.example.chat"))

    @Test fun `a ledger survives a round trip through its rows`() {
        val ledger = EgressLedger()
        ledger.record(app, Provider.ANTHROPIC, "api.anthropic.com", Signal.SNI, 100, blocked = false)
        ledger.record(app, Provider.ANTHROPIC, "api.anthropic.com", Signal.DNS, 250, blocked = true)

        val restored = EgressLedger().apply { restore(ledger.snapshot().map { it.toRow() }) }
        val row = restored.snapshot().single().toRow()
        assertEquals(ledger.snapshot().single().toRow(), row)
        assertEquals(2, row.connections)
        assertEquals(1, row.blocked)
        assertEquals(100, row.firstSeen)
        assertEquals(setOf(Signal.SNI, Signal.DNS), row.signals)
    }

    @Test fun `recording after a restore continues the counts`() {
        val ledger = EgressLedger()
        ledger.record(app, Provider.OPENAI, "api.openai.com", Signal.SNI, 100, blocked = false)
        val restored = EgressLedger().apply { restore(ledger.snapshot().map { it.toRow() }) }
        restored.record(app, Provider.OPENAI, "api.openai.com", Signal.SNI, 300, blocked = false)
        val row = restored.snapshot().single()
        assertEquals(2, row.connections)
        assertEquals(100, row.firstSeen)
        assertEquals(300, row.lastSeen)
    }

    @Test fun `traffic recorded before the restore lands is merged, not overwritten`() {
        val saved = EgressLedger().apply {
            record(app, Provider.OPENAI, "api.openai.com", Signal.SNI, 100, blocked = true)
        }.snapshot().map { it.toRow() }

        val live = EgressLedger()
        live.record(app, Provider.OPENAI, "api.openai.com", Signal.SNI, 500, blocked = false)
        live.restore(saved)
        val row = live.snapshot().single()
        assertEquals(2, row.connections)
        assertEquals(1, row.blocked)
        assertEquals(100, row.firstSeen)
        assertEquals(500, row.lastSeen)
    }

    @Test fun `version moves on every change`() {
        val ledger = EgressLedger()
        val v0 = ledger.version
        ledger.record(app, Provider.OPENAI, "api.openai.com", Signal.SNI, 1, blocked = false)
        assertNotEquals(v0, ledger.version)
        val v1 = ledger.version
        ledger.clear()
        assertNotEquals(v1, ledger.version)
        assertTrue(ledger.snapshot().isEmpty())
    }

    @Test fun `policy restores exactly the saved blocks`() {
        val policy = EgressPolicy().apply { block("a", Provider.OPENAI) }
        policy.restore(listOf("b" to Provider.GROQ))
        assertEquals(setOf("b" to Provider.GROQ), policy.blocks())
    }

    @Test fun `a retransmitted hello keeps its decision and is counted once`() {
        val ledger = EgressLedger()
        val policy = EgressPolicy().apply { block("com.example.chat", Provider.OPENAI) }
        val inspector = EgressInspector(Attributor { app }, ledger, policy, clock = { 5L })
        val pkt = Fixtures.ipv4Tcp("10.215.173.1", 50000, "162.159.140.245", 443, Fixtures.clientHello)

        val first = inspector.onTcpPacket(pkt)
        val again = inspector.onTcpPacket(pkt)
        assertEquals(first, again)
        assertEquals(1, ledger.snapshot().single().connections)
    }
}
