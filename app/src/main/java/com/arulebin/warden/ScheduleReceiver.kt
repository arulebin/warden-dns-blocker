package com.arulebin.warden

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/** Fires at the scheduled start/stop times: arms or disarms protection, then
 *  reschedules the next day's alarms. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        Rules.init(context)
        Stats.init(context)

        val shouldBeOn = Prefs.scheduleEnabled && Scheduler.isWithinWindow()
        if (shouldBeOn && !WardenState.running) {
            // Only auto-start if VPN consent was already granted (can't prompt from the background).
            if (VpnService.prepare(context) == null) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WardenVpnService::class.java).setAction(WardenVpnService.ACTION_CONNECT),
                )
            }
        } else if (!shouldBeOn && WardenState.running) {
            context.startService(
                Intent(context, WardenVpnService::class.java).setAction(WardenVpnService.ACTION_DISCONNECT),
            )
        }

        Scheduler.apply(context) // re-arm for the next day
    }
}
