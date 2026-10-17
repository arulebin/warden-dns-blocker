package com.arulebin.warden

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM)

@Composable
fun HomeScreen(onToggle: (Boolean) -> Unit, insets: PaddingValues) {
    val context = LocalContext.current
    val running = WardenState.running

    // The tunnel takes a moment (and maybe a consent dialog) to come up; show that
    // instead of a dead tap. Cleared when the service reports in, or after a timeout
    // if the user backed out of the consent dialog.
    var arming by remember { mutableStateOf(false) }
    LaunchedEffect(running) { arming = false }
    LaunchedEffect(arming) {
        if (arming) {
            delay(8000)
            arming = false
        }
    }
    val guard = when {
        running -> GuardState.On
        arming -> GuardState.Arming
        else -> GuardState.Off
    }

    var privateDns by remember { mutableStateOf(isPrivateDnsActive(context)) }
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) privateDns = isPrivateDnsActive(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var filter by rememberSaveable { mutableIntStateOf(0) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val entries = QueryLog.entries
    val blockedCount = entries.count { it.blocked }
    val shown = when (filter) {
        1 -> entries.filter { it.blocked }
        2 -> entries.filter { !it.blocked }
        else -> entries.toList()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = insets.calculateTopPadding(),
            bottom = insets.calculateBottomPadding(),
        ),
    ) {
        item(key = "header") { HomeHeader() }

        item(key = "private-dns") {
            AnimatedVisibility(
                visible = privateDns,
                enter = expandVertically(Motion.settle()) + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                PrivateDnsBanner {
                    runCatching { context.startActivity(Intent("android.settings.WIRELESS_SETTINGS")) }
                }
            }
        }

        item(key = "radar") {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                RadarShield(
                    state = guard,
                    lookups = entries.take(36),
                    onToggle = {
                        when (guard) {
                            GuardState.On -> onToggle(false)
                            GuardState.Off -> {
                                arming = true
                                onToggle(true)
                            }
                            GuardState.Arming -> Unit
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 340.dp).aspectRatio(1f),
                )
            }
        }

        item(key = "status") { StatusHeadline(guard) }
        item(key = "stats") { StatsStrip() }

        item(key = "activity-header") {
            SectionHeader(
                title = "Recent lookups",
                supporting = "Tap a domain to block or allow it.",
                trailing = {
                    AnimatedVisibility(
                        visible = entries.isNotEmpty(),
                        enter = fadeIn() + scaleIn(Motion.bouncy(), initialScale = 0.8f),
                        exit = fadeOut() + scaleOut(targetScale = 0.8f),
                    ) {
                        WardenButton(
                            "Clear",
                            onClick = {
                                QueryLog.clear()
                                expandedId = null
                            },
                            kind = ButtonKind.Ghost,
                        )
                    }
                },
            )
        }

        item(key = "filter") {
            Segmented(
                options = listOf("All", "Blocked", "Allowed"),
                selected = filter,
                onSelect = {
                    filter = it
                    expandedId = null
                },
                counts = listOf(entries.size, blockedCount, entries.size - blockedCount),
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        if (shown.isEmpty()) {
            item(key = "empty-$filter") {
                val (title, body) = when {
                    filter == 1 -> "No blocked lookups" to "When Warden stops a domain, it shows up here."
                    filter == 2 -> "No allowed lookups" to "Lookups that pass the filter show up here."
                    guard == GuardState.On -> "Listening" to "Open any app and its lookups will show up here as they happen."
                    else -> "Nothing to show yet" to "Turn on protection and lookups from your apps will appear here."
                }
                Group(Modifier.animateItem(), padding = 0.dp) {
                    EmptyState(WardenIcons.Activity, title, body)
                }
            }
        } else {
            itemsIndexed(shown, key = { _, e -> e.id }) { i, entry ->
                ActivityRow(
                    entry = entry,
                    expanded = expandedId == entry.id,
                    showDivider = i > 0,
                    onClick = { expandedId = if (expandedId == entry.id) null else entry.id },
                    modifier = Modifier.animateItem().groupItem(i, shown.size),
                )
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    val c = Warden.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WardenIcons.Shield, contentDescription = null, modifier = Modifier.size(24.dp), tint = c.guard)
        Spacer(Modifier.width(8.dp))
        Text(
            "Warden",
            style = Warden.type.title.copy(fontSize = 22.sp, letterSpacing = (-0.6).sp),
            color = c.text,
        )
        Spacer(Modifier.weight(1f))
        ThemeToggle()
    }
}

/** Sun/moon button: the glyph spins half a turn and swaps as the whole palette crossfades. */
@Composable
private fun ThemeToggle() {
    val c = Warden.colors
    val dark = c.isDark
    val source = remember { MutableInteractionSource() }
    val rotation by animateFloatAsState(if (dark) 180f else 0f, Motion.bouncy(), label = "themeSpin")

    Box(
        Modifier
            .size(48.dp)
            .bouncyPress(source, 0.85f)
            .clip(CircleShape)
            .clickable(
                interactionSource = source,
                indication = ripple(bounded = false, radius = 24.dp, color = c.guard),
                role = Role.Button,
            ) { Prefs.updateThemeMode(if (dark) ThemeMode.Light else ThemeMode.Dark) }
            .semantics { contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.surface).graphicsLayer { rotationZ = rotation },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = dark,
                transitionSpec = {
                    (fadeIn(Motion.fade(200)) + scaleIn(Motion.bouncy(), initialScale = 0.4f)) togetherWith
                        (fadeOut(Motion.fade(120)) + scaleOut(Motion.fade(120), targetScale = 0.4f))
                },
                label = "themeIcon",
            ) { isDark ->
                Icon(
                    if (isDark) WardenIcons.Sun else WardenIcons.Moon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = c.text,
                )
            }
        }
    }
}

@Composable
private fun StatusHeadline(guard: GuardState) {
    val c = Warden.colors
    AnimatedContent(
        targetState = guard,
        transitionSpec = {
            (slideInVertically(Motion.settle()) { it / 3 } + fadeIn(Motion.fade(240))) togetherWith
                (slideOutVertically(Motion.fade(160)) { -it / 3 } + fadeOut(Motion.fade(140))) using
                SizeTransform(clip = false)
        },
        contentAlignment = Alignment.TopCenter,
        label = "status",
    ) { state ->
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                when (state) {
                    GuardState.On -> "You're protected"
                    GuardState.Arming -> "Turning on…"
                    GuardState.Off -> "Protection is off"
                },
                style = Warden.type.headline,
                color = if (state == GuardState.On) c.guard else c.text,
                textAlign = TextAlign.Center,
            )
            Text(
                when (state) {
                    GuardState.On -> "Warden is screening lookups from every app. Tap the shield to turn it off."
                    GuardState.Arming -> "If Android asks to set up a connection, approve it."
                    GuardState.Off -> "Tap the shield to start filtering every app on this phone."
                },
                style = Warden.type.body,
                color = c.textDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StatsStrip() {
    val c = Warden.colors
    val decided = Stats.blockedTotal + Stats.passedTotal
    val rate = if (decided == 0L) 0f else Stats.blockedTotal.toFloat() / decided
    val meter by animateFloatAsState(rate, Motion.settle(), label = "blockRate")

    Group(Modifier.padding(top = 24.dp), padding = 0.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Stat("Blocked today", Modifier.weight(1f)) {
                RollingNumber(Stats.blockedToday, Warden.type.number, c.blocked)
            }
            StatDivider()
            Stat("All time", Modifier.weight(1f)) {
                RollingNumber(Stats.blockedTotal, Warden.type.number, c.text)
            }
            StatDivider()
            Stat("Block rate", Modifier.weight(1f)) {
                RollingNumber((rate * 100).roundToLong(), Warden.type.number, c.text, suffix = "%")
            }
        }
        // Share of all lookups that were blocked, as a thin bar along the bottom edge.
        Box(Modifier.fillMaxWidth().height(4.dp).background(c.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(meter.coerceIn(0f, 1f)).fillMaxHeight().background(c.blocked))
        }
    }
}

@Composable
private fun Stat(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        value()
        Text(label, style = Warden.type.caption, color = Warden.colors.textDim, maxLines = 1)
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).height(36.dp).background(Warden.colors.line))
}

@Composable
private fun ActivityRow(
    entry: QueryLog.Entry,
    expanded: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Warden.colors
    val accent = if (entry.blocked) c.blocked else c.guard
    val accentSoft = if (entry.blocked) c.blockedSoft else c.guardSoft
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, Motion.settle(), label = "chevron")
    val tint by animateColorAsState(if (expanded) c.surfaceRaised else c.surface, Motion.fade(), label = "rowBg")

    Column(modifier.background(tint)) {
        if (showDivider) {
            Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(c.line.copy(alpha = 0.6f)))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Hide actions" else "Show actions", onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(
                if (entry.blocked) WardenIcons.Ban else WardenIcons.CircleCheck,
                tint = accent,
                background = accentSoft,
                size = 36.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.domain,
                    style = Warden.type.mono,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${if (entry.blocked) "Blocked" else "Allowed"} at ${timeFormat.format(Date(entry.time))}",
                    style = Warden.type.caption,
                    color = if (entry.blocked) c.blocked else c.textDim,
                )
            }
            Icon(
                WardenIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevron },
                tint = c.textFaint,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.settle()) + fadeIn(Motion.fade()),
            exit = shrinkVertically(Motion.fade(180)) + fadeOut(Motion.fade(120)),
        ) {
            ActivityActions(entry)
        }
    }
}

@Composable
private fun ActivityActions(entry: QueryLog.Entry) {
    val toaster = LocalToaster.current
    val gate = rememberPinGate("A PIN is required to loosen your filters.")
    val domain = Validate.normalizeDomain(entry.domain)
    val inBlocklist = domain in Rules.userBlocked
    val inAllowlist = domain in Rules.userAllowed

    Row(
        Modifier.fillMaxWidth().padding(start = 62.dp, end = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            inAllowlist -> WardenButton("Stop allowing", kind = ButtonKind.Tonal, onClick = {
                Rules.removeAllowed(domain)
                toaster.show("Stopped allowing $domain", "Undo") { Rules.addAllowed(domain) }
            })
            inBlocklist -> WardenButton("Unblock", kind = ButtonKind.Tonal, onClick = {
                gate.run {
                    Rules.removeBlocked(domain)
                    toaster.show("Unblocked $domain", "Undo") { Rules.addBlocked(domain) }
                }
            })
            entry.blocked -> WardenButton("Always allow", icon = WardenIcons.CircleCheck, kind = ButtonKind.Tonal, onClick = {
                gate.run {
                    Rules.addAllowed(domain)
                    toaster.show("Always allowing $domain", "Undo") { Rules.removeAllowed(domain) }
                }
            })
            else -> WardenButton("Block", icon = WardenIcons.Ban, kind = ButtonKind.Danger, onClick = {
                Rules.addBlocked(domain)
                toaster.show("Blocked $domain", "Undo") { Rules.removeBlocked(domain) }
            })
        }
    }
}

@Composable
private fun PrivateDnsBanner(onOpen: () -> Unit) {
    val c = Warden.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .clip(GroupShape)
            .background(c.warnSoft)
            .clickable(onClickLabel = "Open network settings", onClick = onOpen)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(WardenIcons.Alert, contentDescription = null, modifier = Modifier.size(20.dp), tint = c.warn)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Private DNS can bypass Warden", style = Warden.type.titleSmall, color = c.text)
            Text(
                "Turn off Private DNS so every lookup goes through the filter.",
                style = Warden.type.bodySmall,
                color = c.textDim,
            )
            Text("Open network settings", style = Warden.type.label, color = c.warn, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun isPrivateDnsActive(context: Context): Boolean {
    val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
    return mode != null && mode != "off"
}
