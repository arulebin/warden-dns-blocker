package com.arulebin.warden

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Auto-arm protection during a daily window using exact alarms. The window can
 * wrap past midnight (e.g. 22:00 → 06:00). Note: alarms don't survive a reboot
 * (that would need a boot receiver), and background-start relies on prior VPN
 * consent + the exact-alarm exemption.
 */
object Scheduler {

    fun apply(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context, ACTION_START))
        am.cancel(pendingIntent(context, ACTION_STOP))
        if (!Prefs.scheduleEnabled) return
        scheduleDaily(context, am, ACTION_START, Prefs.scheduleStartHour)
        scheduleDaily(context, am, ACTION_STOP, Prefs.scheduleEndHour)
    }

    fun isWithinWindow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val start = Prefs.scheduleStartHour
        val end = Prefs.scheduleEndHour
        return if (start <= end) hour in start until end else (hour >= start || hour < end)
    }

    private fun scheduleDaily(context: Context, am: AlarmManager, action: String, hour: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        val pi = pendingIntent(context, action)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } catch (e: SecurityException) {
            // Exact-alarm permission not granted — fall back to an inexact alarm.
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    private fun pendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    const val ACTION_START = "com.arulebin.warden.schedule.START"
    const val ACTION_STOP = "com.arulebin.warden.schedule.STOP"
}
