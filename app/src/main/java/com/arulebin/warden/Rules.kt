package com.arulebin.warden

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The blocking decision. Precedence: allowlist beats everything, then the
 * user blocklist, then the imported list, then any enabled category. All
 * matching is subdomain-aware ("example.com" also matches "ads.example.com").
 *
 * User lists live in SharedPreferences. The big category/imported lists are
 * cached as compact hash files in filesDir (see [DomainSet]) and loaded on a
 * background thread; the VPN waits on [awaitLoaded] so it never filters with a
 * half-loaded list.
 */
object Rules {
    private const val FILE = "warden_rules"
    private var sp: SharedPreferences? = null
    private var dir: File? = null

    val userBlocked = mutableStateListOf<String>()
    val userAllowed = mutableStateListOf<String>()

    private val sets = ConcurrentHashMap<String, DomainSet>()
    /** Observable per-source domain counts, for the UI. */
    val sourceCounts = mutableStateMapOf<String, Int>()

    private val loaded = CountDownLatch(1)

    fun init(context: Context) {
        if (sp != null) return
        val app = context.applicationContext
        val p = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        dir = app.filesDir

        val savedBlocked = p.getStringSet("blocked", null)
        if (savedBlocked == null) {
            userBlocked.addAll(DEFAULT_BLOCKED.sorted())
            persistBlocked()
        } else {
            userBlocked.addAll(savedBlocked.sorted())
        }
        p.getStringSet("allowed", null)?.let { userAllowed.addAll(it.sorted()) }

        // Big lists can take a moment to read; keep that off the main thread.
        Thread({
            try {
                loadSet("imported")
                for (c in Lists.CATEGORIES) {
                    if (Prefs.isCategoryEnabled(c.key)) loadSet("cat:${c.key}")
                }
            } finally {
                loaded.countDown()
            }
        }, "warden-rules-load").start()
    }

    /** Blocks until cached lists are in memory (or the timeout passes). Call off the main thread. */
    fun awaitLoaded(timeoutMs: Long = 20_000) {
        loaded.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private val DEFAULT_BLOCKED = listOf("doubleclick.net", "ads.example.com", "blocked-test.com")

    fun isAllowed(host: String): Boolean = matches(host.lowercase().trimEnd('.'), userAllowed)

    fun isBlocked(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (matches(h, userAllowed)) return false
        if (matches(h, userBlocked)) return true
        if (sets["imported"]?.containsHost(h) == true) return true
        for (c in Lists.CATEGORIES) {
            if (Prefs.isCategoryEnabled(c.key) && sets["cat:${c.key}"]?.containsHost(h) == true) return true
        }
        return false
    }

    private fun matches(host: String, list: List<String>): Boolean = climb(host) { list.contains(it) }

    /** Test the host and each parent suffix. */
    private inline fun climb(host: String, test: (String) -> Boolean): Boolean {
        var name = host
        while (true) {
            if (test(name)) return true
            val dot = name.indexOf('.')
            if (dot < 0) return false
            name = name.substring(dot + 1)
        }
    }

    private fun norm(raw: String) = raw.lowercase().trim()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .substringBefore('/').trimEnd('.')

    fun addBlocked(raw: String) {
        val d = norm(raw)
        if (d.isNotEmpty() && !userBlocked.contains(d)) {
            userBlocked.add(d); userBlocked.sort(); persistBlocked()
        }
    }

    fun removeBlocked(domain: String) {
        if (userBlocked.remove(domain)) persistBlocked()
    }

    fun addAllowed(raw: String) {
        val d = norm(raw)
        if (d.isNotEmpty() && !userAllowed.contains(d)) {
            userAllowed.add(d); userAllowed.sort(); persistAllowed()
        }
    }

    fun removeAllowed(domain: String) {
        if (userAllowed.remove(domain)) persistAllowed()
    }

    private fun persistBlocked() = sp?.edit()?.putStringSet("blocked", userBlocked.toSet())?.apply() ?: Unit
    private fun persistAllowed() = sp?.edit()?.putStringSet("allowed", userAllowed.toSet())?.apply() ?: Unit

    // --- cached domain sets (categories / imported) ---
    private fun binFor(source: String) = File(dir, source.replace(':', '_') + ".bin")
    private fun legacyTxtFor(source: String) = File(dir, source.replace(':', '_') + ".txt")

    fun hasCache(source: String) = binFor(source).exists()

    /** When [source] was last downloaded, in epoch millis (0 = never). */
    fun updatedAt(source: String): Long = sp?.getLong("updated_$source", 0L) ?: 0L

    fun loadSet(source: String) {
        val bin = binFor(source)
        val legacy = legacyTxtFor(source)
        val set = when {
            bin.exists() -> DomainSet.readFrom(bin)
            // One-time migration from the old one-domain-per-line cache.
            legacy.exists() -> DomainSet.Builder().apply {
                legacy.forEachLine { if (it.isNotBlank()) add(it.trim()) }
            }.build().also {
                it.writeTo(bin)
                legacy.delete()
            }
            else -> null
        }
        if (set != null) {
            sets[source] = set
            sourceCounts[source] = set.size
        } else {
            sets.remove(source)
            sourceCounts[source] = 0
        }
    }

    fun saveSet(source: String, domains: Collection<String>) {
        val b = DomainSet.Builder(maxOf(16, domains.size))
        domains.forEach { b.add(it) }
        saveSet(source, b.build())
    }

    fun saveSet(source: String, set: DomainSet) {
        set.writeTo(binFor(source))
        sp?.edit()?.putLong("updated_$source", System.currentTimeMillis())?.apply()
        sets[source] = set
        sourceCounts[source] = set.size
    }

    fun unloadSet(source: String) {
        sets.remove(source)
        sourceCounts[source] = 0
    }

    fun count(source: String): Int = sourceCounts[source] ?: 0
}
