package com.arulebin.warden

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = WordmarkStyle.copy(fontSize = 20.sp, letterSpacing = 4.sp))

        Panel {
            PanelHeader("UPSTREAM DNS")
            EmptyHint("Allowed lookups are forwarded to this resolver.")
            DnsOption("Google", "8.8.8.8")
            DnsOption("Cloudflare", "1.1.1.1")
            DnsOption("Quad9", "9.9.9.9")
            AddField("custom IPv4", buttonLabel = "Set") { Prefs.updateUpstreamDns(it.trim()) }
            Mono("Current: ${Prefs.upstreamDns}", Brand.textDim, 12)
        }

        Panel {
            PanelHeader("DISARM LOCK")
            if (Prefs.hasPin()) {
                EmptyHint("A PIN is required to turn protection off.")
                Text(
                    "Remove PIN",
                    color = Brand.danger,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { Prefs.clearPin() }.padding(vertical = 4.dp),
                )
            } else {
                EmptyHint("Set a PIN so protection can't be switched off without it.")
                AddField("choose a PIN", buttonLabel = "Set") { if (it.length >= 3) Prefs.setPin(it) }
            }
        }

        Panel {
            PanelHeader("SCHEDULE") {
                Switch(
                    checked = Prefs.scheduleEnabled,
                    onCheckedChange = { Prefs.updateScheduleEnabled(it); Scheduler.apply(context) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Brand.ink,
                        checkedTrackColor = Brand.shield,
                        uncheckedThumbColor = Brand.textDim,
                        uncheckedTrackColor = Brand.surfaceHi,
                        uncheckedBorderColor = Brand.line,
                    ),
                )
            }
            EmptyHint("Automatically arm protection during these hours (needs consent granted once).")
            HourStepper("From", Prefs.scheduleStartHour) {
                Prefs.setSchedule(it, Prefs.scheduleEndHour); Scheduler.apply(context)
            }
            HourStepper("To", Prefs.scheduleEndHour) {
                Prefs.setSchedule(Prefs.scheduleStartHour, it); Scheduler.apply(context)
            }
        }
    }
}

@Composable
private fun DnsOption(name: String, ip: String) {
    val selected = Prefs.upstreamDns == ip
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Prefs.updateUpstreamDns(ip) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(name, color = Brand.text, fontSize = 15.sp)
            Mono(ip, Brand.textDim, 12)
        }
        Text(if (selected) "●" else "○", color = if (selected) Brand.shield else Brand.textDim, fontSize = 16.sp)
    }
}

@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Brand.textDim, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StepperButton("−") { onChange((hour + 23) % 24) }
            Mono(String.format("%02d:00", hour), Brand.text, 16, FontWeight.Bold)
            StepperButton("+") { onChange((hour + 1) % 24) }
        }
    }
}

@Composable
private fun StepperButton(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Brand.surfaceHi).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Brand.shield, fontSize = 18.sp)
    }
}
