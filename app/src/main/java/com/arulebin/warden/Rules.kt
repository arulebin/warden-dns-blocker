package com.arulebin.warden

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import java.io.File

/**
 * The blocking decision. Precedence: allowlist beats everything, then the
 * user blocklist, then the imported list, then any enabled category. All
 * matching is subdomain-aware ("example.com" also matches "ads.example.com").
 *
 * User lists live in SharedPreferences; the big category/imported lists are
 * cached as one-domain-per-line files in filesDir and loaded into memory.
 */
object Rules {
    private const val FILE = "warden_rules"
    private var sp: SharedPreferences? = null
    private var dir: File? = null

    val userBlocked = mutableStateListOf<String>()
    val userAllowed = mutableStateListOf<String>()

    private val sets = HashMap<String, HashSet<String>>()
    /** Observable per-source domain counts, for the UI. */
    val sourceCounts = mutableStateMapOf<String, Int>()

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

        loadSet("imported")
        for (c in Lists.CATEGORIES) {
            if (Prefs.isCategoryEnabled(c.key)) loadSet("cat:${c.key}")
        }
    }

    private val DEFAULT_BLOCKED = listOf("doubleclick.net", "ads.example.com", "blocked-test.com")

    fun isBlocked(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (matches(h, userAllowed)) return false
        if (matches(h, userBlocked)) return true
        if (inSet(h, sets["imported"])) return true
        for (c in Lists.CATEGORIES) {
            if (Prefs.isCategoryEnabled(c.key) && inSet(h, sets["cat:${c.key}"])) return true
        }
        return false
    }

    private fun matches(host: String, list: List<String>): Boolean = climb(host) { list.contains(it) }
    private fun inSet(host: String, set: HashSet<String>?): Boolean {
        if (set.isNullOrEmpty()) return false
        return climb(host) { set.contains(it) }
    }

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
    private fun fileFor(source: String) = File(dir, source.replace(':', '_') + ".txt")

    fun loadSet(source: String) {
        val f = fileFor(source)
        if (f.exists()) {
            val s = HashSet<String>()
            f.forEachLine { if (it.isNotBlank()) s.add(it.trim()) }
            sets[source] = s
            sourceCounts[source] = s.size
        } else {
            sets.remove(source)
            sourceCounts[source] = 0
        }
    }

    fun saveSet(source: String, domains: Collection<String>) {
        fileFor(source).bufferedWriter().use { w ->
            for (d in domains) { w.write(d); w.newLine() }
        }
        loadSet(source)
    }

    fun unloadSet(source: String) {
        sets.remove(source)
        sourceCounts[source] = 0
    }

    fun count(source: String): Int = sourceCounts[source] ?: 0
}
