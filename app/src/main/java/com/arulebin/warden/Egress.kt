package com.arulebin.warden

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.arulebin.egressledger.classify.Provider
import com.arulebin.egressledger.ledger.EgressInspector
import com.arulebin.egressledger.ledger.EgressLedger
import com.arulebin.egressledger.ledger.EgressPolicy
import java.io.File
import java.util.concurrent.Executors

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
    private const val FLUSH_EVERY_MS = 10_000L

    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    val ledger = EgressLedger()
    val policy = EgressPolicy()

    private var sp: SharedPreferences? = null
    private var store: LedgerStore? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "warden-ledger-io").apply { isDaemon = true } }
    @Volatile private var savedVersion = -1L
    @Volatile private var lastFlush = 0L

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

    /** Saves the ledger if it changed. [force] skips the throttle (used when the VPN stops). */
    fun flush(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlush < FLUSH_EVERY_MS) return
        val version = ledger.version
        if (version == savedVersion) return
        lastFlush = now
        val rows = ledger.snapshot().map { it.toRow() }
        io.execute {
            store?.save(rows)
            savedVersion = version
        }
    }

    fun clear() {
        ledger.clear()
        io.execute {
            store?.clear()
            savedVersion = ledger.version
        }
    }

    /** Writes the ledger as CSV for export — the corpus format, one row per app and provider. */
    fun exportCsv(dir: File): File {
        val file = File(dir, "egress-ledger.csv")
        file.bufferedWriter().use { w ->
            w.write("package,provider,kind,hosts,signals,first_seen,last_seen,connections,blocked\n")
            for (e in ledger.snapshot()) {
                val cells = listOf(
                    e.app.label, e.provider.displayName, e.provider.kind.name,
                    e.hosts.sorted().joinToString(" "), e.signals.joinToString(" ") { it.name },
                    e.firstSeen.toString(), e.lastSeen.toString(), e.connections.toString(), e.blocked.toString(),
                )
                w.write(cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
                w.write("\n")
            }
        }
        return file
    }
}
