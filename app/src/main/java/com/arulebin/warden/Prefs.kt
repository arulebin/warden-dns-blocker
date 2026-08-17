package com.arulebin.warden

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App settings, persisted and exposed as Compose-observable state. */
object Prefs {
    private const val FILE = "warden_prefs"
    private var sp: SharedPreferences? = null

    var upstreamDns by mutableStateOf("8.8.8.8"); private set
    var lockEnabled by mutableStateOf(false); private set
    var scheduleEnabled by mutableStateOf(false); private set
    var scheduleStartHour by mutableStateOf(22); private set
    var scheduleEndHour by mutableStateOf(6); private set
    val enabledCategories = mutableStateListOf<String>()

    private var pinHash: String? = null
    private var pinSalt: String? = null

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        upstreamDns = p.getString("upstream", "8.8.8.8") ?: "8.8.8.8"
        lockEnabled = p.getBoolean("lock_enabled", false)
        scheduleEnabled = p.getBoolean("sched_enabled", false)
        scheduleStartHour = p.getInt("sched_start", 22)
        scheduleEndHour = p.getInt("sched_end", 6)
        pinHash = p.getString("pin_hash", null)
        pinSalt = p.getString("pin_salt", null)
        p.getStringSet("categories", emptySet())?.let { enabledCategories.addAll(it) }
    }

    fun updateUpstreamDns(value: String) {
        upstreamDns = value
        sp?.edit()?.putString("upstream", value)?.apply()
    }

    fun updateScheduleEnabled(value: Boolean) {
        scheduleEnabled = value
        sp?.edit()?.putBoolean("sched_enabled", value)?.apply()
    }

    fun setSchedule(start: Int, end: Int) {
        scheduleStartHour = start
        scheduleEndHour = end
        sp?.edit()?.putInt("sched_start", start)?.putInt("sched_end", end)?.apply()
    }

    fun isCategoryEnabled(key: String) = enabledCategories.contains(key)

    fun setCategoryEnabled(key: String, enabled: Boolean) {
        if (enabled) {
            if (!enabledCategories.contains(key)) enabledCategories.add(key)
        } else {
            enabledCategories.remove(key)
        }
        sp?.edit()?.putStringSet("categories", enabledCategories.toSet())?.apply()
    }

    // --- PIN lock ---
    fun hasPin() = pinHash != null

    fun setPin(pin: String) {
        val salt = Lock.newSalt()
        pinSalt = salt
        pinHash = Lock.hash(pin, salt)
        lockEnabled = true
        sp?.edit()
            ?.putString("pin_hash", pinHash)
            ?.putString("pin_salt", salt)
            ?.putBoolean("lock_enabled", true)
            ?.apply()
    }

    fun clearPin() {
        pinHash = null
        pinSalt = null
        lockEnabled = false
        sp?.edit()?.remove("pin_hash")?.remove("pin_salt")?.putBoolean("lock_enabled", false)?.apply()
    }

    fun verifyPin(pin: String): Boolean {
        val h = pinHash ?: return true
        val s = pinSalt ?: return true
        return Lock.hash(pin, s) == h
    }
}
