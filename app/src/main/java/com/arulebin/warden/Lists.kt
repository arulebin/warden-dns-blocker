package com.arulebin.warden

import androidx.compose.runtime.mutableStateMapOf
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Curated category lists and user-imported lists. Categories with a [url] are
 * fetched (hosts format) and cached; bundled categories ship their domains
 * inline. Fetches run off the main thread; [busy] / [lastError] are observable
 * so the UI can show progress.
 */
object Lists {
    data class Category(
        val key: String,
        val title: String,
        val desc: String,
        val url: String?,
        val bundled: List<String> = emptyList(),
    )

    val CATEGORIES = listOf(
        Category(
            "ads", "Ads & trackers",
            "Known advertising and analytics servers.",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        ),
        Category(
            "adult", "Adult content",
            "Pornography and adult sites.",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn/hosts",
        ),
        Category(
            "social", "Social & focus",
            "Major social networks — handy during study or focus time.",
            null,
            bundled = listOf(
                "facebook.com", "instagram.com", "tiktok.com", "twitter.com",
                "x.com", "reddit.com", "snapchat.com",
            ),
        ),
    )

    val busy = mutableStateMapOf<String, Boolean>()
    val lastError = mutableStateMapOf<String, String>()

    private val io = Executors.newCachedThreadPool()

    fun categoryFor(key: String) = CATEGORIES.firstOrNull { it.key == key }

    fun enableCategory(key: String) {
        val cat = categoryFor(key) ?: return
        Prefs.setCategoryEnabled(key, true)
        if (cat.url == null) {
            Rules.saveSet("cat:$key", cat.bundled)
        } else {
            fetchInto("cat:$key", cat.url)
        }
    }

    fun disableCategory(key: String) {
        Prefs.setCategoryEnabled(key, false)
        Rules.unloadSet("cat:$key")
    }

    fun refreshCategory(key: String) {
        val cat = categoryFor(key) ?: return
        if (cat.url == null) Rules.saveSet("cat:$key", cat.bundled) else fetchInto("cat:$key", cat.url)
    }

    fun importFromUrl(url: String) = fetchInto("imported", url)

    fun clearImported() = Rules.saveSet("imported", emptyList())

    private fun fetchInto(source: String, url: String) {
        busy[source] = true
        lastError.remove(source)
        io.execute {
            try {
                Rules.saveSet(source, downloadHosts(url))
            } catch (e: Exception) {
                lastError[source] = e.message ?: "Download failed"
            } finally {
                busy[source] = false
            }
        }
    }

    /** Parse a hosts-format file ("0.0.0.0 domain" lines, or bare domains). */
    private fun downloadHosts(urlStr: String): List<String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 25000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Warden/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val domains = LinkedHashSet<String>()
            conn.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) continue
                    val parts = line.split(Regex("\\s+"))
                    val host = (if (parts.size >= 2) parts[1] else parts[0]).lowercase().trimEnd('.')
                    if (host.isEmpty() || !host.contains('.')) continue
                    if (host == "localhost" || host == "broadcasthost" ||
                        host == "0.0.0.0" || host == "127.0.0.1" || host == "::1"
                    ) continue
                    domains.add(host)
                }
            }
            return domains.toList()
        } finally {
            conn.disconnect()
        }
    }
}
