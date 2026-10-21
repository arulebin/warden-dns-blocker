package com.arulebin.warden

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.arulebin.egressledger.classify.Provider
import com.arulebin.egressledger.ledger.EgressInspector
import com.arulebin.egressledger.ledger.EgressLedger
import com.arulebin.egressledger.ledger.EgressPolicy
import java.io.Writer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-wide home of the AI egress ledger: the live ledger and block policy, their
 * persistence, and the gate that decides whether the feature runs at all.
 *
 * The ledger needs getConnectionOwnerUid (Android 10+) and the full tunnel, so it is
 * offered only on [supported] devices and only when the user has turned it on;
 * everywhere else Warden behaves exactly as the DNS-only filter it was.
 */
object Egress {
    private const val FILE = "warden_egress"
    private const val FLUSH_DELAY_MS = 5_000L

    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    val ledger = EgressLedger()
    val policy = EgressPolicy()

    private var sp: SharedPreferences? = null
    private var store: LedgerStore? = null
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "warden-ledger-io").apply { isDaemon = true } }
    private val flushPending = AtomicBoolean()
    @Volatile private var savedVersion = -1L

    fun init(context: Context) {
        if (sp != null) return
        val app = context.applicationContext
        val p = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        policy.restore(
            p.getStringSet("blocks", emptySet()).orEmpty().mapNotNull { entry ->
                val (pkg, name) = entry.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
                Provider.entries.firstOrNull { it.name == name }?.let { pkg to it }
            },
        )
        val s = LedgerStore(app)
        store = s
        // Small table, but still disk I/O: keep it off whichever thread called init.
        io.execute {
            ledger.restore(s.load())
            savedVersion = ledger.version
        }
    }

    /** Whether the ledger (and with it the full tunnel) should run for the next VPN start. */
    val active: Boolean get() = supported && Prefs.ledgerEnabled

    /** A fresh inspector bound to this device's attribution, or null when the ledger is off. */
    fun newInspector(context: Context): EgressInspector? =
        if (active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            EgressInspector(UidAttributor(context), ledger, policy)
        } else {
            null
        }

    fun setBlocked(pkg: String, provider: Provider, blocked: Boolean) {
        if (blocked) policy.block(pkg, provider) else policy.unblock(pkg, provider)
        sp?.edit()?.putStringSet("blocks", policy.blocks().map { "${it.first}\t${it.second.name}" }.toSet())?.apply()
    }

    fun isBlocked(pkg: String, provider: Provider) = policy.isBlocked(pkg, provider)

    /**
     * Saves the ledger soon. Calls within [FLUSH_DELAY_MS] coalesce into one write, and
     * the write always comes after the latest change, so a burst's last record is never
     * left unsaved. [force] writes now (used when the VPN stops).
     */
    fun flush(force: Boolean = false) {
        if (force) {
            io.execute(::save)
        } else if (flushPending.compareAndSet(false, true)) {
            io.schedule({
                flushPending.set(false)
                save()
            }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Runs on [io]. */
    private fun save() {
        val version = ledger.version
        if (version == savedVersion) return
        store?.save(ledger.snapshot().map { it.toRow() })
        savedVersion = version
    }

    fun clear() {
        ledger.clear()
        io.execute {
            store?.clear()
            savedVersion = ledger.version
        }
    }

    /** Writes the ledger as CSV — the corpus format, one row per app and provider, times in epoch millis. */
    fun exportCsv(out: Writer) {
        out.write("package,provider,kind,hosts,signals,first_seen,last_seen,connections,blocked\n")
        for (e in ledger.snapshot()) {
            val cells = listOf(
                e.app.label, e.provider.displayName, e.provider.kind.name,
                e.hosts.sorted().joinToString(" "), e.signals.joinToString(" ") { it.name },
                e.firstSeen.toString(), e.lastSeen.toString(), e.connections.toString(), e.blocked.toString(),
            )
            out.write(cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
            out.write("\n")
        }
        out.flush()
    }
}
