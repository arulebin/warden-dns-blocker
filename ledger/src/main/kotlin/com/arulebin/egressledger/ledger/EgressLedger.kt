package com.arulebin.egressledger.ledger

import com.arulebin.egressledger.capture.FlowKey
import com.arulebin.egressledger.classify.Provider
import java.util.concurrent.ConcurrentHashMap

/** The app that owns a connection. A shared UID can hold several packages, so all are kept. */
data class AppIdentity(val uid: Int, val packages: List<String>) {
    val label: String get() = packages.firstOrNull() ?: "uid:$uid"
}

/** Binds a flow to the app that opened it. On Android: getConnectionOwnerUid (API 29+). */
fun interface Attributor {
    fun attribute(flow: FlowKey): AppIdentity?
}

/** How the endpoint was seen: the TLS SNI, or a DNS query the tunnel already answers (Warden's path). */
enum class Signal { SNI, DNS }

/** One (app, provider) row of the ledger. */
class LedgerEntry(val app: AppIdentity, val provider: Provider) {
    val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val signals: MutableSet<Signal> = ConcurrentHashMap.newKeySet()
    @Volatile var firstSeen: Long = 0; private set
    @Volatile var lastSeen: Long = 0; private set
    @Volatile var connections: Int = 0; private set
    @Volatile var blocked: Int = 0; private set

    @Synchronized
    internal fun record(host: String, signal: Signal, at: Long, wasBlocked: Boolean) {
        if (connections == 0) firstSeen = at
        lastSeen = at
        connections++
        if (wasBlocked) blocked++
        hosts += host
        signals += signal
    }
}

/**
 * The ledger itself: every contact between an app and a third-party AI provider,
 * aggregated per (package, provider). Payloads are never stored — only who talked to whom.
 */
class EgressLedger {
    private val entries = ConcurrentHashMap<Pair<String, Provider>, LedgerEntry>()

    fun record(app: AppIdentity, provider: Provider, host: String, signal: Signal, at: Long, blocked: Boolean) {
        entries.computeIfAbsent(app.label to provider) { LedgerEntry(app, provider) }
            .record(host, signal, at, blocked)
    }

    fun snapshot(): List<LedgerEntry> = entries.values.sortedByDescending { it.lastSeen }

    fun providersFor(pkg: String): Set<Provider> =
        entries.keys.filter { it.first == pkg }.map { it.second }.toSet()
}

/** The control half: per-app, per-provider blocks set by the user from the ledger screen. */
class EgressPolicy {
    private val blocks = ConcurrentHashMap.newKeySet<Pair<String, Provider>>()

    fun block(pkg: String, provider: Provider) { blocks += pkg to provider }
    fun unblock(pkg: String, provider: Provider) { blocks -= pkg to provider }
    fun isBlocked(pkg: String, provider: Provider): Boolean = (pkg to provider) in blocks
}
