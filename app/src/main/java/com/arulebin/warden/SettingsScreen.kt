package com.arulebin.warden

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class DnsPreset(val name: String, val ip: String, val note: String)

private val DNS_PRESETS = listOf(
    DnsPreset("Google", "8.8.8.8", "Reliable and widely used"),
    DnsPreset("Cloudflare", "1.1.1.1", "Fast, with a strict privacy policy"),
    DnsPreset("Quad9", "9.9.9.9", "Also stops known malicious domains"),
)

private sealed interface PinFlow {
    data object None : PinFlow
    data object VerifyToRemove : PinFlow
    data object VerifyToChange : PinFlow
    data object Choose : PinFlow
    data class Confirm(val first: String) : PinFlow
}

@Composable
fun SettingsScreen(insets: PaddingValues) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var pinFlow by remember { mutableStateOf<PinFlow>(PinFlow.None) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = insets.calculateTopPadding(),
            bottom = insets.calculateBottomPadding(),
        ),
    ) {
        item(key = "title") { ScreenTitle("Settings") }

        item(key = "appearance") {
            Column {
                SectionHeader("Appearance")
                Segmented(
                    options = listOf("System", "Light", "Dark"),
                    selected = Prefs.themeMode.ordinal,
                    onSelect = { Prefs.updateThemeMode(ThemeMode.entries[it]) },
                    icons = listOf(WardenIcons.Monitor, WardenIcons.Sun, WardenIcons.Moon),
                )
            }
        }

        item(key = "dns") {
            Column {
                SectionHeader("DNS server", supporting = "Lookups that aren't blocked are answered by this server.")
                val current = Prefs.upstreamDns
                Group(padding = 8.dp, spacing = 0.dp) {
                    Column(Modifier.selectableGroup()) {
                        DNS_PRESETS.forEach { p ->
                            DnsOption(p.name, p.ip, p.note, selected = current == p.ip) {
                                Prefs.updateUpstreamDns(p.ip)
                            }
                        }
                        if (DNS_PRESETS.none { it.ip == current }) {
                            DnsOption("Custom", current, "Set by you", selected = true) {}
                        }
                    }
                    Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                        InputRow(
                            placeholder = "Custom, like 1.0.0.1",
                            actionLabel = "Use",
                            keyboardType = KeyboardType.Decimal,
                            validate = Validate::ipv4,
                            onSubmit = {
                                Prefs.updateUpstreamDns(it)
                                toaster.show("Using $it for lookups")
                            },
                        )
                    }
                }
            }
        }

        item(key = "lock") {
            Column {
                SectionHeader("Disarm lock", supporting = "Require a PIN before protection can be turned off.")
                LockCard(
                    onSet = { pinFlow = PinFlow.Choose },
                    onChange = { pinFlow = PinFlow.VerifyToChange },
                    onRemove = { pinFlow = PinFlow.VerifyToRemove },
                )
            }
        }

        item(key = "schedule") {
            Column {
                SectionHeader("Schedule", supporting = "Turn protection on automatically during set hours.")
                ScheduleCard(onChanged = { Scheduler.apply(context) })
            }
        }

        item(key = "footer") {
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            }
            Text(
                "Warden ${version ?: ""}".trim(),
                style = Warden.type.caption,
                color = Warden.colors.textFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            )
        }
    }

    key(pinFlow) {
        when (val flow = pinFlow) {
            PinFlow.None -> Unit
            PinFlow.VerifyToRemove -> PinDialog(
                title = "Enter your PIN",
                subtitle = "Confirm it's you before removing the lock.",
                onDismiss = { pinFlow = PinFlow.None },
                onSubmit = { pin ->
                    if (Prefs.verifyPin(pin)) {
                        Prefs.clearPin()
                        pinFlow = PinFlow.None
                        toaster.show("PIN removed")
                        null
                    } else {
                        "That PIN isn't right. Try again."
                    }
                },
            )
            PinFlow.VerifyToChange -> PinDialog(
                title = "Enter your current PIN",
                subtitle = "Then you can choose a new one.",
                onDismiss = { pinFlow = PinFlow.None },
                onSubmit = { pin ->
                    if (Prefs.verifyPin(pin)) {
                        pinFlow = PinFlow.Choose
                        null
                    } else {
                        "That PIN isn't right. Try again."
                    }
                },
            )
            PinFlow.Choose -> PinDialog(
                title = "Choose a PIN",
                subtitle = "Use at least 4 digits. You'll need it to turn protection off.",
                onDismiss = { pinFlow = PinFlow.None },
                onSubmit = { pin ->
                    pinFlow = PinFlow.Confirm(pin)
                    null
                },
            )
            is PinFlow.Confirm -> PinDialog(
                title = "Enter it again",
                subtitle = "Type the same PIN to confirm.",
                onDismiss = { pinFlow = PinFlow.None },
                onSubmit = { pin ->
                    if (pin == flow.first) {
                        Prefs.setPin(pin)
                        pinFlow = PinFlow.None
                        toaster.show("PIN set")
                        null
                    } else {
                        "Those PINs don't match. Try again."
                    }
                },
            )
        }
    }
}

@Composable
private fun DnsOption(name: String, ip: String, note: String, selected: Boolean, onSelect: () -> Unit) {
    val c = Warden.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RadioMark(selected)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = Warden.type.titleSmall, color = c.text)
                Spacer(Modifier.width(8.dp))
                Text(ip, style = Warden.type.mono.copy(fontSize = Warden.type.caption.fontSize), color = c.textDim)
            }
            Text(note, style = Warden.type.bodySmall, color = c.textDim)
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val c = Warden.colors
    val ring by animateColorAsState(if (selected) c.guard else c.line, Motion.fade(), label = "radioRing")
    val dot by animateDpAsState(if (selected) 12.dp else 0.dp, Motion.bouncy(), label = "radioDot")
    Box(
        Modifier.size(24.dp).border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(dot).clip(CircleShape).background(c.guard))
    }
}

@Composable
private fun LockCard(onSet: () -> Unit, onChange: () -> Unit, onRemove: () -> Unit) {
    val c = Warden.colors
    // lockEnabled is the observable flag; it flips together with the stored PIN.
    val hasPin = Prefs.lockEnabled && Prefs.hasPin()
    val tileBg by animateColorAsState(if (hasPin) c.guardSoft else c.surfaceRaised, Motion.fade(), label = "lockTile")
    val tileFg by animateColorAsState(if (hasPin) c.guard else c.textDim, Motion.fade(), label = "lockIcon")

    Group {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(WardenIcons.Lock, tint = tileFg, background = tileBg, size = 44.dp)
            AnimatedContent(
                targetState = hasPin,
                transitionSpec = {
                    (fadeIn(Motion.fade(220)) + slideInVertically(Motion.settle()) { it / 4 }) togetherWith
                        fadeOut(Motion.fade(120)) using SizeTransform(clip = false)
                },
                modifier = Modifier.weight(1f),
                label = "lockText",
            ) { on ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (on) "PIN is on" else "No PIN set", style = Warden.type.titleSmall, color = c.text)
                    Text(
                        if (on) {
                            "Protection can only be turned off with your PIN."
                        } else {
                            "Anyone holding this phone can turn protection off."
                        },
                        style = Warden.type.bodySmall,
                        color = c.textDim,
                    )
                }
            }
        }
        AnimatedContent(
            targetState = hasPin,
            transitionSpec = {
                (fadeIn(Motion.fade(220)) + scaleIn(Motion.bouncy(), initialScale = 0.92f)) togetherWith
                    (fadeOut(Motion.fade(120)) + scaleOut(Motion.fade(120), targetScale = 0.96f))
            },
            label = "lockActions",
        ) { on ->
            if (on) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WardenButton("Change PIN", onClick = onChange, kind = ButtonKind.Tonal, modifier = Modifier.weight(1f))
                    WardenButton("Remove PIN", onClick = onRemove, kind = ButtonKind.Danger, modifier = Modifier.weight(1f))
                }
            } else {
                WardenButton("Set a PIN", onClick = onSet, icon = WardenIcons.Lock, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ScheduleCard(onChanged: () -> Unit) {
    val c = Warden.colors
    val enabled = Prefs.scheduleEnabled
    val start = Prefs.scheduleStartHour
    val end = Prefs.scheduleEndHour
    val dim by animateFloatAsState(if (enabled) 1f else 0.55f, Motion.fade(), label = "scheduleDim")

    Group {
        ToggleRow(
            title = "Use schedule",
            supporting = if (enabled) {
                "Turns on at ${hourLabel(start)} and off at ${hourLabel(end)}, every day."
            } else {
                "Off. Protection only changes when you tap the shield."
            },
            checked = enabled,
            onCheckedChange = {
                Prefs.updateScheduleEnabled(it)
                onChanged()
            },
        )
        Column(
            Modifier.graphicsLayer { alpha = dim },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DayBar(start, end)
            HourStepper("Starts", start) {
                Prefs.setSchedule(it, end)
                onChanged()
            }
            HourStepper("Ends", end) {
                Prefs.setSchedule(start, it)
                onChanged()
            }
        }
    }
}

private fun hourLabel(hour: Int) = "%02d:00".format(hour)

/** The day as 24 cells; the active window fills in, wrapping past midnight. */
@Composable
private fun DayBar(start: Int, end: Int) {
    val c = Warden.colors
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = "Active from ${hourLabel(start)} to ${hourLabel(end)}"
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (h in 0 until 24) {
                val active = if (start <= end) h in start until end else (h >= start || h < end)
                val color by animateColorAsState(if (active) c.guard else c.surfaceRaised, Motion.fade(260), label = "hourCell")
                val height by animateFloatAsState(if (active) 1f else 0.6f, Motion.bouncy(), label = "hourHeight")
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(height)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("00", "06", "12", "18").forEach {
                Text(it, style = Warden.type.caption, color = c.textFaint, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    val c = Warden.colors
    Row(
        Modifier.fillMaxWidth().clip(ControlShape).background(c.surfaceRaised).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Warden.type.label, color = c.textDim, modifier = Modifier.weight(1f))
        IconAction(WardenIcons.Minus, "$label an hour earlier", { onChange((hour + 23) % 24) }, tint = c.text, size = 36.dp)
        AnimatedContent(
            targetState = hour,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInVertically(Motion.settle()) { it * dir } + fadeIn(Motion.fade(160))) togetherWith
                    (slideOutVertically(Motion.settle()) { -it * dir } + fadeOut(Motion.fade(100))) using
                    SizeTransform(clip = true)
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.widthIn(min = 72.dp),
            label = "hour",
        ) { h ->
            Text(hourLabel(h), style = Warden.type.titleSmall.copy(fontFeatureSettings = "tnum"), color = c.text, textAlign = TextAlign.Center)
        }
        IconAction(WardenIcons.Plus, "$label an hour later", { onChange((hour + 1) % 24) }, tint = c.text, size = 36.dp)
    }
}
