package com.arulebin.egressledger

import com.arulebin.egressledger.classify.Provider
import com.arulebin.egressledger.ledger.AppIdentity
import com.arulebin.egressledger.ledger.Attributor
import com.arulebin.egressledger.ledger.Decision
import com.arulebin.egressledger.ledger.EgressInspector
import com.arulebin.egressledger.ledger.EgressLedger
import com.arulebin.egressledger.ledger.EgressPolicy
import com.arulebin.egressledger.ledger.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** End to end, minus the device: packet → SNI → provider → app → ledger → decision. */
class EgressInspectorTest {
    private val photoApp = AppIdentity(10_123, listOf("com.example.photoeditor"))
    private val attributor = Attributor { flow -> if (flow.srcPort == 51234) photoApp else null }
    private val ledger = EgressLedger()
    private val policy = EgressPolicy()
    private val inspector = EgressInspector(attributor, ledger, policy, clock = { 1_000L })

    private fun helloPacket() = Fixtures.ipv4Tcp("10.215.173.1", 51234, "162.159.140.245", 443, Fixtures.clientHello)

    @Test fun `records an attributed AI egress and lets it through by default`() {
        val result = assertNotNull(inspector.onTcpPacket(helloPacket()))
        assertEquals(Provider.OPENAI, result.provider)
        assertEquals(photoApp, result.app)
        assertEquals(Decision.PASS, result.decision)

        val row = ledger.snapshot().single()
        assertEquals("com.example.photoeditor", row.app.label)
        assertEquals(setOf("api.openai.com"), row.hosts)
        assertEquals(setOf(Signal.SNI), row.signals)
        assertEquals(1, row.connections)
    }

    @Test fun `drops the flow once the user blocks that app and provider`() {
        policy.block("com.example.photoeditor", Provider.OPENAI)
        assertEquals(Decision.DROP, inspector.onTcpPacket(helloPacket())!!.decision)
        assertEquals(1, ledger.snapshot().single().blocked)
    }

    @Test fun `ignores non-TLS ports and non-AI hosts`() {
        val http = Fixtures.ipv4Tcp("10.215.173.1", 51234, "162.159.140.245", 80, Fixtures.clientHello)
        assertNull(inspector.onTcpPacket(http))
        assertNull(inspector.onDnsQuery(helloPacketFlow(), "www.wikipedia.org"))
        assertEquals(0, ledger.snapshot().size)
    }

    @Test fun `unattributable flows are reported but never enter the ledger`() {
        val stranger = Fixtures.ipv4Tcp("10.215.173.1", 40000, "162.159.140.245", 443, Fixtures.clientHello)
        val result = assertNotNull(inspector.onTcpPacket(stranger))
        assertNull(result.app)
        assertEquals(0, ledger.snapshot().size)
    }

    private fun helloPacketFlow() = com.arulebin.egressledger.capture.Packets.parseTcp(helloPacket())!!.flow
}
