package com.arulebin.warden

import androidx.compose.runtime.mutableStateMapOf
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Curated category lists and user-imported lists. A category merges one or more
 * remote lists (hosts files, plain domain lists, or wildcard lists) into a
 * single [DomainSet]; bundled categories ship their domains inline. Fetches run
 * off the main thread; [busy] / [lastError] are observable so the UI can show
 * progress.
 */
object Lists {
    data class Category(
        val key: String,
        val title: String,
        val desc: String,
        val urls: List<String>,
        val bundled: List<String> = emptyList(),
        /** Rough download size, shown before the user commits to it. */
        val downloadHint: String? = null,
    )

    /**
     * Adult sources, merged and deduplicated. No list catches every site; these
     * three are actively maintained and overlap only partly, so together they
     * cover far more than any one alone.
     */
    val ADULT_SOURCES = listOf(
        // OISD NSFW: large, curated, wildcard format (*.domain).
        "https://nsfw.oisd.nl/domainswild",
        // StevenBlack porn-only: the adult extensions without the ad/malware base list.
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
        // The Block List Project: very broad, includes many hosted-blog subdomains.
        "https://blocklistproject.github.io/Lists/porn.txt",
    )

    const val ADULT = "adult"

    val CATEGORIES = listOf(
        Category(
            "ads", "Ads & trackers",
            "Known advertising and analytics servers.",
            listOf("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
            downloadHint = "About 4 MB",
        ),
        Category(
            ADULT, "Adult content",
            "Over a million adult domains from three maintained lists.",
            ADULT_SOURCES,
            downloadHint = "About 38 MB",
        ),
        Category(
            "social", "Social & focus",
            "Major social networks — handy during study or focus time.",
            emptyList(),
            bundled = listOf(
                "facebook.com", "instagram.com", "tiktok.com", "twitter.com",
                "x.com", "reddit.com", "snapchat.com",
            ),
        ),
    )

    private const val STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    val busy = mutableStateMapOf<String, Boolean>()
    val lastError = mutableStateMapOf<String, String>()

    private val io = Executors.newCachedThreadPool()

    fun categoryFor(key: String) = CATEGORIES.firstOrNull { it.key == key }

    fun enableCategory(key: String) {
        val cat = categoryFor(key) ?: return
        val source = "cat:$key"
        Prefs.setCategoryEnabled(key, true)
        when {
            cat.urls.isEmpty() -> Rules.saveSet(source, cat.bundled)
            // Re-enabling soon after disabling reuses the cache instead of re-downloading.
            Rules.hasCache(source) && !isStale(source) -> io.execute { Rules.loadSet(source) }
            else -> fetchInto(source, cat.urls)
        }
    }

    fun disableCategory(key: String) {
        Prefs.setCategoryEnabled(key, false)
        Rules.unloadSet("cat:$key")
    }

    fun refreshCategory(key: String) {
        val cat = categoryFor(key) ?: return
        if (cat.urls.isEmpty()) Rules.saveSet("cat:$key", cat.bundled) else fetchInto("cat:$key", cat.urls)
    }

    /** Re-download enabled categories whose lists are more than a week old. */
    fun refreshStale() {
        for (cat in CATEGORIES) {
            val source = "cat:${cat.key}"
            if (cat.urls.isNotEmpty() && Prefs.isCategoryEnabled(cat.key) && isStale(source) && busy[source] != true) {
                fetchInto(source, cat.urls)
            }
        }
    }

    private fun isStale(source: String) = System.currentTimeMillis() - Rules.updatedAt(source) > STALE_AFTER_MS

    fun importFromUrl(url: String) = fetchInto("imported", listOf(url))

    fun clearImported() = Rules.saveSet("imported", emptyList())

    private fun fetchInto(source: String, urls: List<String>) {
        if (busy[source] == true) return
        busy[source] = true
        lastError.remove(source)
        io.execute {
            try {
                val builder = DomainSet.Builder(1 shl 18)
                var failures = 0
                var lastFailure = ""
                for (url in urls) {
                    try {
                        downloadInto(url, builder)
                    } catch (e: Exception) {
                        failures++
                        lastFailure = e.message ?: "Download failed"
                    }
                }
                when {
                    // Keep whatever list we already had rather than replacing it with nothing.
                    failures == urls.size -> lastError[source] = lastFailure
                    else -> {
                        Rules.saveSet(source, builder.build())
                        if (failures > 0) {
                            lastError[source] = "${urls.size - failures} of ${urls.size} sources downloaded"
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                lastError[source] = "Not enough memory to load this list"
            } finally {
                busy[source] = false
            }
        }
    }

    /**
     * Streams a list straight into [builder] without holding it as text. Understands
     * hosts files ("0.0.0.0 domain"), plain domain lists, wildcard lists ("*.domain")
     * and Adblock-style rules ("||domain^").
     */
    private fun downloadInto(urlStr: String, builder: DomainSet.Builder) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Warden/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val host = parseLine(raw) ?: continue
                    builder.add(host)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private val WHITESPACE = Regex("\\s+")

    internal fun parseLine(raw: String): String? {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return null
        val parts = line.split(WHITESPACE)
        var host = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
        if (host.startsWith("||")) host = host.substring(2).substringBefore('^')
        host = host.removePrefix("*.").removePrefix(".").trimEnd('.')
        if (host.isEmpty() || !host.contains('.')) return null
        if (host == "localhost" || host == "localhost.localdomain" || host == "broadcasthost" ||
            host == "0.0.0.0" || host == "127.0.0.1" || host == "::1" || host == "local"
        ) return null
        if (host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }) return null
        return host
    }
}
