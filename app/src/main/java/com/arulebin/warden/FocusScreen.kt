package com.arulebin.warden

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val DAY_MS = 86_400_000L
private val MILESTONES = listOf(1, 3, 7, 14, 30, 60, 90, 180, 365)

private fun milestoneName(days: Long) = when (days) {
    1L -> "First day"
    3L -> "Three days"
    7L -> "One week"
    14L -> "Two weeks"
    30L -> "One month"
    60L -> "Two months"
    90L -> "Ninety days"
    180L -> "Half a year"
    365L -> "One year"
    else -> if (days % 365 == 0L) "${days / 365} years" else "$days days"
}

/** "2d 4h" / "4h 12m" / "12m": compact, for "next milestone in …". */
private fun shortDuration(ms: Long): String {
    val totalMinutes = max(0L, ms) / 60_000
    val d = totalMinutes / (60 * 24)
    val h = (totalMinutes / 60) % 24
    val m = totalMinutes % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

@Composable
fun FocusScreen(insets: PaddingValues, onArm: () -> Unit) {
    val toaster = LocalToaster.current

    // Tick once a second, aligned to the wall clock so seconds change together.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000 - System.currentTimeMillis() % 1000)
            now = System.currentTimeMillis()
        }
    }

    val start = Prefs.focusStart
    val running = start > 0
    val elapsed = if (running) max(0L, now - start) else 0L
    val days = elapsed / DAY_MS
    val best = max(Prefs.focusBest, elapsed)

    var confirmReset by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = insets.calculateTopPadding(),
            bottom = insets.calculateBottomPadding(),
        ),
    ) {
        item(key = "title") {
            ScreenTitle("Focus") {
                Text("Build discipline one day at a time.", style = Warden.type.body, color = Warden.colors.textDim)
            }
        }

        item(key = "hero") {
            StreakHero(
                running = running,
                elapsed = elapsed,
                onStart = {
                    Prefs.startFocus()
                    toaster.show("Streak started. Day one begins now.")
                },
                onReset = { confirmReset = true },
                onPickDate = { pickDate = true },
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item(key = "stats") {
            Group(Modifier.padding(top = 12.dp), padding = 0.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FocusStat("Best streak", Modifier.weight(1f)) {
                        RollingNumber(best / DAY_MS, Warden.type.number, Warden.colors.text, suffix = "d")
                    }
                    Box(Modifier.width(1.dp).height(36.dp).background(Warden.colors.line))
                    FocusStat("Resets", Modifier.weight(1f)) {
                        RollingNumber(Prefs.focusResets.toLong(), Warden.type.number, Warden.colors.text)
                    }
                    Box(Modifier.width(1.dp).height(36.dp).background(Warden.colors.line))
                    FocusStat("Started", Modifier.weight(1f)) {
                        Text(
                            if (running) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(start)) else "Not yet",
                            style = Warden.type.titleSmall.copy(fontFeatureSettings = "tnum"),
                            color = Warden.colors.text,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        item(key = "milestones") {
            Column {
                SectionHeader("Milestones", supporting = "Each one unlocks as your streak reaches it.")
                MilestoneGrid(days = if (running) days else -1L)
            }
        }

        item(key = "adult") {
            Column {
                SectionHeader("Adult site blocking", supporting = "Stops explicit sites from loading in every app and browser.")
                AdultFilterCard(onArm)
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset your streak?",
            body = "Your current run of $days ${if (days == 1L) "day" else "days"} ends and a new one starts now. Your best streak stays saved.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = {
                Prefs.resetFocus()
                confirmReset = false
                toaster.show("Streak reset. Day one begins now.")
            },
            onDismiss = { confirmReset = false },
        )
    }

    if (pickDate) {
        StartDateDialog(
            initial = if (running) start else System.currentTimeMillis(),
            onDismiss = { pickDate = false },
            onPick = { picked ->
                Prefs.startFocus(minOf(picked, System.currentTimeMillis()))
                pickDate = false
                toaster.show("Start date updated")
            },
        )
    }
}

@Composable
private fun StreakHero(
    running: Boolean,
    elapsed: Long,
    onStart: () -> Unit,
    onReset: () -> Unit,
    onPickDate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Warden.colors
    val days = elapsed / DAY_MS
    val next = MILESTONES.firstOrNull { it > days }?.toLong() ?: ((days / 365) + 1) * 365
    val previous = if (days >= 365) (days / 365) * 365 else MILESTONES.lastOrNull { it <= days }?.toLong() ?: 0L
    val progress = if (!running) 0f else
        ((elapsed - previous * DAY_MS).toFloat() / ((next - previous) * DAY_MS)).coerceIn(0f, 1f)

    // Sweep in from empty the first time the ring appears, then follow the clock.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val sweep by animateFloatAsState(
        if (appeared) progress else 0f,
        tween(if (Warden.reducedMotion) 0 else 900, easing = FastOutSlowInEasing),
        label = "streakRing",
    )

    Group(modifier, padding = 20.dp, spacing = 16.dp) {
        Box(
            Modifier.fillMaxWidth().height(240.dp).clearAndSetSemantics {
                contentDescription = "$days ${if (days == 1L) "day" else "days"} in your streak"
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(232.dp)) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(c.surfaceRaised, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (sweep > 0f) {
                    drawArc(c.guard, -90f, 360f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    // A bright knob marks the leading edge.
                    val angle = Math.toRadians((-90f + 360f * sweep).toDouble())
                    val r = (size.width - stroke) / 2
                    val knob = Offset(size.width / 2 + (cos(angle) * r).toFloat(), size.height / 2 + (sin(angle) * r).toFloat())
                    drawCircle(c.onGuard, radius = stroke * 0.22f, center = knob)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(WardenIcons.Flame, contentDescription = null, modifier = Modifier.size(22.dp), tint = if (running) c.guard else c.textFaint)
                RollingNumber(days, Warden.type.display.copy(fontSize = 60.sp, lineHeight = 64.sp, letterSpacing = (-2).sp), c.text)
                Text(if (days == 1L) "day" else "days", style = Warden.type.label, color = c.textDim)
            }
        }

        AnimatedContent(
            targetState = running,
            transitionSpec = {
                (fadeIn(Motion.fade(260)) + slideInVertically(Motion.settle()) { it / 4 }) togetherWith
                    fadeOut(Motion.fade(120)) using SizeTransform(clip = false)
            },
            label = "streakBody",
        ) { isRunning ->
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (isRunning) {
                    val totalSeconds = elapsed / 1000
                    Text(
                        "%02dh %02dm %02ds".format((totalSeconds / 3600) % 24, (totalSeconds / 60) % 60, totalSeconds % 60),
                        style = Warden.type.title.copy(fontFeatureSettings = "tnum"),
                        color = c.text,
                    )
                    Text(
                        "${milestoneName(next)} in ${shortDuration(next * DAY_MS - elapsed)}",
                        style = Warden.type.bodySmall,
                        color = c.textDim,
                        textAlign = TextAlign.Center,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WardenButton("Edit start", onClick = onPickDate, icon = WardenIcons.Calendar, kind = ButtonKind.Tonal, modifier = Modifier.weight(1f))
                        WardenButton("Reset", onClick = onReset, icon = WardenIcons.Refresh, kind = ButtonKind.Danger, modifier = Modifier.weight(1f))
                    }
                } else {
                    Text(
                        "Start a streak and watch it grow. Every day you keep it going counts.",
                        style = Warden.type.bodySmall,
                        color = c.textDim,
                        textAlign = TextAlign.Center,
                    )
                    WardenButton("Start streak", onClick = onStart, icon = WardenIcons.Flame, modifier = Modifier.fillMaxWidth())
                    WardenButton("Already going? Set a start date", onClick = onPickDate, kind = ButtonKind.Ghost)
                }
            }
        }
    }
}

@Composable
private fun FocusStat(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        value()
        Text(label, style = Warden.type.caption, color = Warden.colors.textDim, maxLines = 1)
    }
}

@Composable
private fun MilestoneGrid(days: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MILESTONES.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m -> MilestoneCell(m.toLong(), reached = days >= m, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MilestoneCell(milestone: Long, reached: Boolean, modifier: Modifier = Modifier) {
    val c = Warden.colors
    val bg by animateColorAsState(if (reached) c.guardSoft else c.surface, Motion.fade(), label = "milestoneBg")
    Column(
        modifier
            .clip(TileShape)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = reached,
                transitionSpec = { scaleIn(Motion.bouncy(), initialScale = 0.3f) + fadeIn() togetherWith fadeOut() },
                label = "milestoneIcon",
            ) { done ->
                if (done) {
                    IconTile(WardenIcons.Check, tint = c.onGuard, background = c.guard, size = 28.dp)
                } else {
                    IconTile(WardenIcons.Trophy, tint = c.textFaint, background = c.surfaceRaised, size = 28.dp)
                }
            }
        }
        Text(milestoneName(milestone), style = Warden.type.label, color = if (reached) c.text else c.textDim, maxLines = 1)
        Text("Day $milestone", style = Warden.type.caption, color = c.textFaint)
    }
}

@Composable
private fun AdultFilterCard(onArm: () -> Unit) {
    val c = Warden.colors
    val toaster = LocalToaster.current
    val gate = rememberPinGate("A PIN is required to weaken adult site blocking.")
    val source = "cat:${Lists.ADULT}"
    val on = Prefs.isCategoryEnabled(Lists.ADULT)
    val busy = Lists.busy[source] == true
    val error = Lists.lastError[source]
    val count = Rules.count(source)
    val protecting = WardenState.running
    var confirmOff by remember { mutableStateOf(false) }

    val tileBg by animateColorAsState(if (on) c.guard else c.surfaceRaised, Motion.fade(), label = "adultTileBg")
    val tileFg by animateColorAsState(if (on) c.onGuard else c.textDim, Motion.fade(), label = "adultTileFg")

    Group(spacing = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(if (on) WardenIcons.ShieldCheck else WardenIcons.Shield, tint = tileFg, background = tileBg, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (on) "Adult sites are blocked" else "Adult sites aren't blocked", style = Warden.type.titleSmall, color = c.text)
                Text(
                    if (on) "Filtering every app while protection is on." else "Turn this on to filter them everywhere.",
                    style = Warden.type.bodySmall,
                    color = c.textDim,
                )
            }
        }

        AnimatedContent(
            targetState = on,
            transitionSpec = {
                (fadeIn(Motion.fade(240)) + slideInVertically(Motion.settle()) { it / 6 }) togetherWith
                    fadeOut(Motion.fade(120)) using SizeTransform(clip = false)
            },
            label = "adultBody",
        ) { enabled ->
            if (!enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Blocks over a million adult domains from three maintained lists, forces SafeSearch, and stops browsers from routing around the filter.",
                        style = Warden.type.bodySmall,
                        color = c.textDim,
                    )
                    WardenButton(
                        "Block adult sites",
                        icon = WardenIcons.Shield,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            Lists.enableCategory(Lists.ADULT)
                            Prefs.updateSafeSearch(true)
                            Prefs.updateBlockBypass(true)
                            if (!WardenState.running) onArm()
                            toaster.show("Blocking adult sites")
                        },
                    )
                    Text(
                        "Downloads about 38 MB once, then refreshes weekly.",
                        style = Warden.type.caption,
                        color = c.textFaint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ListStatus(busy = busy, count = count, error = error)
                    ToggleRow(
                        title = "Force SafeSearch",
                        supporting = "Google, Bing, DuckDuckGo and YouTube hide explicit results.",
                        checked = Prefs.safeSearch,
                        onCheckedChange = { v ->
                            if (v) Prefs.updateSafeSearch(true) else gate.run { Prefs.updateSafeSearch(false) }
                        },
                    )
                    ToggleRow(
                        title = "Block DNS bypass",
                        supporting = "Stops browsers from using their own encrypted DNS to skip the filter.",
                        checked = Prefs.blockBypass,
                        onCheckedChange = { v ->
                            if (v) Prefs.updateBlockBypass(true) else gate.run { Prefs.updateBlockBypass(false) }
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(updatedLabel(Rules.updatedAt(source)), style = Warden.type.caption, color = c.textFaint, modifier = Modifier.weight(1f))
                        WardenButton("Update now", onClick = { Lists.refreshCategory(Lists.ADULT) }, kind = ButtonKind.Ghost, enabled = !busy)
                    }
                    WardenButton(
                        "Turn off",
                        kind = ButtonKind.Danger,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { gate.run { confirmOff = true } },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = on && !protecting,
            enter = expandVertically(Motion.settle()) + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                Modifier.fillMaxWidth().clip(ControlShape).background(c.warnSoft).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(WardenIcons.Alert, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.warn)
                Text("Protection is off, so nothing is blocked right now.", style = Warden.type.bodySmall, color = c.text, modifier = Modifier.weight(1f))
                WardenButton("Turn on", onClick = onArm, kind = ButtonKind.Ghost)
            }
        }
    }

    if (confirmOff) {
        ConfirmDialog(
            title = "Turn off adult site blocking?",
            body = "Adult sites, forced SafeSearch and bypass blocking will all stop. Your streak isn't affected.",
            confirmLabel = "Turn off",
            destructive = true,
            onConfirm = {
                Lists.disableCategory(Lists.ADULT)
                Prefs.updateSafeSearch(false)
                Prefs.updateBlockBypass(false)
                confirmOff = false
                toaster.show("Adult site blocking turned off")
            },
            onDismiss = { confirmOff = false },
        )
    }
}

@Composable
private fun ListStatus(busy: Boolean, count: Int, error: String?) {
    val c = Warden.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(TileShape).background(if (count > 0) c.guard else c.textFaint))
            if (count > 0) {
                RollingNumber(count.toLong(), Warden.type.label.copy(fontFeatureSettings = "tnum"), c.text)
                Text("adult domains blocked", style = Warden.type.label, color = c.textDim)
            } else {
                Text(if (busy) "Downloading lists…" else "No list downloaded yet", style = Warden.type.label, color = c.textDim)
            }
        }
        AnimatedVisibility(busy, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                IndeterminateBar()
                Text("This can take a minute on slower connections.", style = Warden.type.caption, color = c.textFaint)
            }
        }
        if (error != null && !busy) {
            InlineMessage(
                if (count > 0) "$error. Tap Update now to try the rest." else "Download failed: $error. Check your connection and tap Update now.",
                c.blocked,
            )
        }
    }
}

private fun updatedLabel(at: Long): String {
    if (at == 0L) return "Not downloaded yet"
    val days = (System.currentTimeMillis() - at) / DAY_MS
    return when (days) {
        0L -> "Updated today"
        1L -> "Updated yesterday"
        else -> "Updated $days days ago"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDateDialog(initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val c = Warden.colors
    // DatePicker speaks UTC midnights; convert the local calendar date both ways.
    val local = Calendar.getInstance().apply { timeInMillis = initial }
    val initialUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialUtc,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
            override fun isSelectableYear(year: Int) = year <= Calendar.getInstance().get(Calendar.YEAR)
        },
    )
    val colors = pickerColors()

    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colors,
        confirmButton = {
            WardenButton(
                "Set date",
                kind = ButtonKind.Ghost,
                enabled = state.selectedDateMillis != null,
                onClick = {
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = state.selectedDateMillis ?: return@WardenButton
                    }
                    val picked = Calendar.getInstance().apply {
                        clear()
                        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
                    }
                    onPick(picked.timeInMillis)
                },
            )
        },
        dismissButton = { WardenButton("Cancel", onClick = onDismiss, kind = ButtonKind.Ghost) },
    ) {
        DatePicker(
            state = state,
            colors = colors,
            showModeToggle = false,
            title = {
                Text(
                    "When did your streak start?",
                    style = Warden.type.label,
                    color = c.textDim,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun pickerColors(): DatePickerColors {
    val c = Warden.colors
    return DatePickerDefaults.colors(
        containerColor = c.surface,
        titleContentColor = c.textDim,
        headlineContentColor = c.text,
        weekdayContentColor = c.textDim,
        subheadContentColor = c.textDim,
        navigationContentColor = c.text,
        yearContentColor = c.text,
        currentYearContentColor = c.guard,
        selectedYearContentColor = c.onGuard,
        selectedYearContainerColor = c.guard,
        dayContentColor = c.text,
        disabledDayContentColor = c.textFaint,
        selectedDayContentColor = c.onGuard,
        selectedDayContainerColor = c.guard,
        todayContentColor = c.guard,
        todayDateBorderColor = c.guard,
        dividerColor = c.line,
    )
}
