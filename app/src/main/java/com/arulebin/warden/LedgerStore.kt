package com.arulebin.warden

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.arulebin.egressledger.classify.Provider
import com.arulebin.egressledger.ledger.LedgerRow
import com.arulebin.egressledger.ledger.Signal

/**
 * SQLite persistence for the egress ledger. The ledger is already aggregated (one row
 * per package and provider), so it stays small and is saved by rewriting changed rows
 * rather than by appending events. Platform SQLite keeps Warden free of extra libraries.
 */
class LedgerStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "egress_ledger.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ledger (
                pkg TEXT NOT NULL,
                provider TEXT NOT NULL,
                uid INTEGER NOT NULL,
                packages TEXT NOT NULL,
                hosts TEXT NOT NULL,
                signals TEXT NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                connections INTEGER NOT NULL,
                blocked INTEGER NOT NULL,
                PRIMARY KEY (pkg, provider)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun load(): List<LedgerRow> {
        val rows = ArrayList<LedgerRow>()
        readableDatabase.query("ledger", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                // A provider dropped from the taxonomy since the row was written is skipped, not guessed at.
                val provider = Provider.entries.firstOrNull { it.name == c.getString(c.getColumnIndexOrThrow("provider")) }
                    ?: continue
                rows += LedgerRow(
                    uid = c.getInt(c.getColumnIndexOrThrow("uid")),
                    packages = c.getString(c.getColumnIndexOrThrow("packages")).splitList(),
                    provider = provider,
                    hosts = c.getString(c.getColumnIndexOrThrow("hosts")).splitList().toSet(),
                    signals = c.getString(c.getColumnIndexOrThrow("signals")).splitList()
                        .mapNotNull { s -> Signal.entries.firstOrNull { it.name == s } }.toSet(),
                    firstSeen = c.getLong(c.getColumnIndexOrThrow("first_seen")),
                    lastSeen = c.getLong(c.getColumnIndexOrThrow("last_seen")),
                    connections = c.getInt(c.getColumnIndexOrThrow("connections")),
                    blocked = c.getInt(c.getColumnIndexOrThrow("blocked")),
                )
            }
        }
        return rows
    }

    fun save(rows: List<LedgerRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                val values = ContentValues().apply {
                    put("pkg", r.packages.firstOrNull() ?: "uid:${r.uid}")
                    put("provider", r.provider.name)
                    put("uid", r.uid)
                    put("packages", r.packages.joinToString(SEP))
                    put("hosts", r.hosts.sorted().joinToString(SEP))
                    put("signals", r.signals.joinToString(SEP) { it.name })
                    put("first_seen", r.firstSeen)
                    put("last_seen", r.lastSeen)
                    put("connections", r.connections)
                    put("blocked", r.blocked)
                }
                db.insertWithOnConflict("ledger", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.delete("ledger", null, null)
    }

    private fun String.splitList(): List<String> = if (isEmpty()) emptyList() else split(SEP)

    private companion object {
        /** Package names and hostnames never contain a newline. */
        const val SEP = "\n"
    }
}
