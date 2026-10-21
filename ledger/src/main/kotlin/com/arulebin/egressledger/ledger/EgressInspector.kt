package com.arulebin.egressledger.ledger

import com.arulebin.egressledger.capture.FlowKey
import com.arulebin.egressledger.capture.Packets
import com.arulebin.egressledger.capture.TlsSni
import com.arulebin.egressledger.classify.Provider
import com.arulebin.egressledger.classify.ProviderTaxonomy

enum class Decision { PASS, DROP }

/** What the inspector learned from one packet — returned for logging and for the tests. */
data class Inspection(val host: String, val provider: Provider, val app: AppIdentity?, val decision: Decision)

/**
 * Capture → attribute → classify → record → enforce, for one packet off the tunnel.
 *
 * Only the first data segment of a TLS connection is looked at (the ClientHello);
 * every other packet is passed through untouched and never parsed further.
 */
class EgressInspector(
    private val attributor: Attributor,
    private val ledger: EgressLedger,
    private val policy: EgressPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Recent hellos by flow. A retransmitted ClientHello must get the same decision
     * (a dropped flow stays dropped) without being counted as a second connection.
     */
    private val recent = object : LinkedHashMap<FlowKey, Inspection>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FlowKey, Inspection>?) = size > RECENT_FLOWS
    }

    /** A TCP packet from the tunnel. DROP means the relay resets the connection instead of forwarding it. */
    fun onTcpPacket(packet: ByteArray, length: Int = packet.size): Inspection? {
        if (!Packets.isTlsHandshakeTo443(packet, length)) return null
        val seg = Packets.parseTcp(packet, length) ?: return null
        if (seg.payloadLength == 0) return null
        val host = TlsSni.extract(seg.packet, seg.payloadOffset, seg.payloadLength) ?: return null
        synchronized(recent) { recent[seg.flow] }?.let { return it }
        return inspect(seg.flow, host, Signal.SNI)?.also { synchronized(recent) { recent[seg.flow] = it } }
    }

    /** A DNS query Warden's resolver path already parsed; attributed the same way (UDP 5-tuple). */
    fun onDnsQuery(flow: FlowKey, domain: String): Inspection? = inspect(flow, domain, Signal.DNS)

    private fun inspect(flow: FlowKey, host: String, signal: Signal): Inspection? {
        // Classify before attributing: the UID lookup is a binder call, so skip it for non-AI traffic.
        val provider = ProviderTaxonomy.classify(host) ?: return null
        val app = attributor.attribute(flow)
        val drop = app != null && policy.isBlocked(app.label, provider)
        if (app != null) ledger.record(app, provider, host, signal, clock(), drop)
        return Inspection(host, provider, app, if (drop) Decision.DROP else Decision.PASS)
    }

    private companion object {
        const val RECENT_FLOWS = 256
    }
}
