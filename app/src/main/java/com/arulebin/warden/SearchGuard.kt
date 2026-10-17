package com.arulebin.warden

/**
 * Protections that close gaps a blocklist can't:
 *
 * - **SafeSearch.** Google, Bing, DuckDuckGo and YouTube each publish a "safe"
 *   host that serves their filtered mode. Answering the normal hostname with
 *   the safe host's address forces that mode on for every browser and app,
 *   including explicit image and video results that never touch a blocked domain.
 *
 * - **Bypass blocking.** Browsers can resolve names over encrypted DNS (DoH),
 *   which skips Warden entirely. Failing lookups for well-known DoH endpoints
 *   keeps them on the system resolver. Firefox also checks the canary domain
 *   use-application-dns.net and disables its automatic DoH when it fails.
 */
object SearchGuard {

    private val GOOGLE = Regex("^(www\\.)?google\\.((com?\\.)?[a-z]{2,3})$")

    private val NOT_SEARCH = setOf("org", "net", "dev", "app", "ai")

    private val SAFE_HOSTS = buildMap {
        listOf("bing.com", "www.bing.com").forEach { put(it, "strict.bing.com") }
        listOf("duckduckgo.com", "www.duckduckgo.com", "start.duckduckgo.com", "html.duckduckgo.com")
            .forEach { put(it, "safe.duckduckgo.com") }
        listOf(
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
            "youtubei.googleapis.com", "youtube.googleapis.com", "www.youtube-nocookie.com",
        ).forEach { put(it, "restrictmoderate.youtube.com") }
    }

    /** Hostname to resolve instead of [host] when SafeSearch is on, or null to resolve normally. */
    fun safeTarget(host: String): String? {
        if (!Prefs.safeSearch) return null
        val h = host.lowercase().trimEnd('.')
        GOOGLE.matchEntire(h)?.let { m ->
            // google.org / .net / .dev etc. are not search front-ends.
            if (m.groupValues[2] !in NOT_SEARCH) return "forcesafesearch.google.com"
        }
        return SAFE_HOSTS[h]
    }

    private val BYPASS = hashSetOf(
        // Firefox's canary: a failed lookup turns off its automatic DoH.
        "use-application-dns.net",
        // Apple's iCloud Private Relay canary, harmless elsewhere.
        "mask.icloud.com", "mask-h2.icloud.com",
        // Common DoH endpoints browsers let users pick.
        "dns.google", "dns.google.com", "8888.google",
        "cloudflare-dns.com", "one.one.one.one",
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
        "doh.opendns.com", "doh.familyshield.opendns.com",
        "dns.adguard.com", "dns.adguard-dns.com", "dns-unfiltered.adguard.com",
        "dns.nextdns.io",
        "doh.cleanbrowsing.org",
        "dns.mullvad.net", "doh.mullvad.net",
        "dns.controld.com", "freedns.controld.com",
        "doh.xfinity.com",
        "doh.dns.sb",
        "dns0.eu",
        "doh.libredns.gr",
        "doh.applied-privacy.net",
        "dns.alidns.com",
        "doh.360.cn",
        "dns.twnic.tw",
        "ordns.he.net",
        "dns.switch.ch",
        "dns.digitale-gesellschaft.ch",
    )

    /** True when [host] (or a parent domain) is an encrypted-DNS endpoint and bypass blocking is on. */
    fun isBypass(host: String): Boolean {
        if (!Prefs.blockBypass) return false
        var name = host.lowercase().trimEnd('.')
        while (true) {
            if (name in BYPASS) return true
            val dot = name.indexOf('.')
            if (dot < 0) return false
            name = name.substring(dot + 1)
        }
    }
}
