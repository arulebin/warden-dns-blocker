package com.arulebin.warden

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar

/** Persistent counters for the dashboard. Updated from the VPN threads, written
 *  to disk on a throttle so heavy browsing doesn't hammer storage. */
object Stats {
    private const val FILE = "warden_stats"
    private var sp: SharedPreferences? = null

    var blockedTotal by mutableStateOf(0L); private set
    var passedTotal by mutableStateOf(0L); private set
    var blockedToday by mutableStateOf(0L); private set

    private var todayKey = 0
    private var lastPersist = 0L

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        blockedTotal = p.getLong("blocked_total", 0)
        passedTotal = p.getLong("passed_total", 0)
        todayKey = currentDayKey()
        blockedToday = if (p.getInt("today_key", 0) == todayKey) p.getLong("blocked_today", 0) else 0
    }

    private fun currentDayKey(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    @Synchronized
    fun record(blocked: Boolean) {
        val dk = currentDayKey()
        if (dk != todayKey) {
            todayKey = dk
            blockedToday = 0
        }
        if (blocked) {
            blockedTotal++
            blockedToday++
        } else {
            passedTotal++
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPersist > 2000) {
            persist()
            lastPersist = now
        }
    }

    @Synchronized
    fun persist() {
        sp?.edit()
            ?.putLong("blocked_total", blockedTotal)
            ?.putLong("passed_total", passedTotal)
            ?.putInt("today_key", todayKey)
            ?.putLong("blocked_today", blockedToday)
            ?.apply()
    }
}
