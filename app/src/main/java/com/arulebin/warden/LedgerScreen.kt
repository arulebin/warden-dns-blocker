package com.arulebin.warden

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.arulebin.egressledger.classify.Provider
import com.arulebin.egressledger.ledger.LedgerRow
import com.arulebin.egressledger.ledger.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The AI egress ledger: which apps contacted which AI providers, how often, and a
 * per-provider switch to block each app. Loosening a block (or turning recording
 * off) asks for the PIN, the same as turning protection off.
 */
@Composable
fun LedgerScreen(insets: PaddingValues) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val c = Warden.colors

    // The ledger is written from the VPN thread; poll its change counter while the screen is shown.
    var rows by remember { mutableStateOf(Egress.ledger.snapshot().map { it.toRow() }) }
    LaunchedEffect(Unit) {
        var seen = Egress.ledger.version
        while (true) {
            delay(1_000)
            val v = Egress.ledger.version
            if (v != seen) {
                seen = v
                rows = Egress.ledger.snapshot().map { it.toRow() }
            }
        }
    }
    // Blocks live outside Compose state; bumping this re-reads them after a change.
    var policyVersion by remember { mutableIntStateOf(0) }

    val apps = remember(rows) {
        rows.groupBy { it.packages.firstOrNull() ?: "uid:${it.uid}" }
            .map { (pkg, list) -> pkg to list.sortedByDescending { it.lastSeen } }
            .sortedByDescending { (_, list) -> list.first().lastSeen }
    }

    var pinAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    fun guarded(action: () -> Unit) {
        if (Prefs.lockEnabled && Prefs.hasPin()) pinAction = action else action()
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use(Egress::exportCsv)
        }.isSuccess
        toaster.show(if (ok) "Ledger exported" else "Couldn't write that file")
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
        item(key = "title") {
            ScreenTitle("AI traffic") {
                Text(
                    "Which apps send data to AI services, and a switch to stop them.",
                    style = Warden.type.bodySmall,
                    color = c.textDim,
                )
            }
        }

        item(key = "status") {
            Column {
                SectionHeader("Recording")
                StatusGroup(
                    onToggle = { on ->
                        if (on) setRecording(context, true, toaster) else guarded { setRecording(context, false, toaster) }
                    },
                )
            }
        }

        if (rows.isNotEmpty()) {
            item(key = "summary") { Summary(rows, apps.size) }
        }

        item(key = "apps-header") {
            SectionHeader(
                "Apps",
                supporting = "Only who talked to whom is kept, never what was sent.",
                trailing = if (rows.isEmpty()) null else {
                    { IconAction(WardenIcons.Download, "Export ledger as CSV", { exportLauncher.launch("egress-ledger.csv") }) }
                },
            )
        }

        if (apps.isEmpty()) {
            item(key = "empty") {
                Group {
                    EmptyState(
                        WardenIcons.Sparkle,
                        "No AI traffic yet",
                        if (Prefs.ledgerEnabled) "Apps that reach an AI provider will appear here as they do."
                        else "Turn on recording to see which apps reach AI providers.",
                    )
                }
            }
        }

        apps.forEach { (pkg, entries) ->
            item(key = pkg) {
                AppCard(
                    pkg = pkg,
                    entries = entries,
                    isBlocked = { provider -> policyVersion.let { Egress.isBlocked(pkg, provider) } },
                    onBlockChange = { provider, block ->
                        val apply: () -> Unit = {
                            Egress.setBlocked(pkg, provider, block)
                            policyVersion++
                        }
                        if (block) apply() else guarded(apply)
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }

        if (rows.isNotEmpty()) {
            item(key = "clear") {
                WardenButton(
                    "Clear ledger",
                    onClick = { confirmClear = true },
                    kind = ButtonKind.Danger,
                    icon = WardenIcons.Trash,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear the ledger?",
            body = "Every recorded app and provider is removed. Your blocks stay in place.",
            confirmLabel = "Clear",
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                Egress.clear()
                rows = emptyList()
                toaster.show("Ledger cleared")
            },
        )
    }

    pinAction?.let { action ->
        PinDialog(
            title = "Enter your PIN",
            subtitle = "A PIN is required to loosen AI blocking.",
            onDismiss = { pinAction = null },
            onSubmit = { pin ->
                if (Prefs.verifyPin(pin)) {
                    pinAction = null
                    action()
                    null
                } else {
                    "That PIN isn't right. Try again."
                }
            },
        )
    }
}

/** Turning recording on or off changes the tunnel's routes, so a running VPN is restarted to apply it. */
private fun setRecording(context: Context, on: Boolean, toaster: ToastState) {
    Prefs.updateLedgerEnabled(on)
    if (WardenState.running) {
        context.startService(
            Intent(context, WardenVpnService::class.java).setAction(WardenVpnService.ACTION_RESTART),
        )
    }
    toaster.show(if (on) "Recording AI traffic" else "Stopped recording AI traffic")
}

@Composable
private fun StatusGroup(onToggle: (Boolean) -> Unit) {
    val c = Warden.colors
    Group {
        ToggleRow(
            "Record AI traffic",
            checked = Egress.supported && Prefs.ledgerEnabled,
            onCheckedChange = { if (Egress.supported) onToggle(it) },
            supporting = "Routes every app's traffic through Warden so connections to AI providers " +
                "can be named and blocked. Encrypted content is never opened.",
        )
        when {
            !Egress.supported ->
                InlineMessage("Needs Android 10 or newer.", c.warn)
            !Prefs.ledgerEnabled -> Unit
            !WardenState.running ->
                InlineMessage("Turn on protection from the Shield tab to start recording.", c.textDim, icon = WardenIcons.Shield)
            !WardenState.ledgerActive ->
                InlineMessage("The traffic relay couldn't start, so only DNS lookups are being recorded.", c.warn)
            else ->
                InlineMessage("Recording. QUIC is held back so apps use TCP, where the endpoint is visible.", c.guard, icon = WardenIcons.CircleCheck)
        }
    }
}

@Composable
private fun Summary(rows: List<LedgerRow>, appCount: Int) {
    val c = Warden.colors
    Group(modifier = Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Apps", appCount.toLong())
            Stat("Providers", rows.map { it.provider }.distinct().size.toLong())
            Stat("Connections", rows.sumOf { it.connections.toLong() })
            Stat("Stopped", rows.sumOf { it.blocked.toLong() }, accent = c.blocked)
        }
    }
}

@Composable
private fun Stat(label: String, value: Long, accent: androidx.compose.ui.graphics.Color = Warden.colors.text) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RollingNumber(value, Warden.type.number, accent)
        Text(label, style = Warden.type.caption, color = Warden.colors.textDim)
    }
}

@Composable
private fun AppCard(
    pkg: String,
    entries: List<LedgerRow>,
    isBlocked: (Provider) -> Boolean,
    onBlockChange: (Provider, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Warden.colors
    val (label, icon) = rememberAppInfo(pkg)
    val now = System.currentTimeMillis()
    Group(modifier, spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(TileShape))
            } else {
                IconTile(WardenIcons.Globe, c.textDim, c.surfaceRaised)
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = Warden.type.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pkg,
                    style = Warden.type.mono.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = c.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                DateUtils.getRelativeTimeSpanString(entries.first().lastSeen, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                style = Warden.type.caption,
                color = c.textFaint,
            )
        }
        entries.forEach { row ->
            ProviderRow(row, blocked = isBlocked(row.provider), onBlockChange = { onBlockChange(row.provider, it) })
        }
    }
}

@Composable
private fun ProviderRow(row: LedgerRow, blocked: Boolean, onBlockChange: (Boolean) -> Unit) {
    val c = Warden.colors
    val counts = buildString {
        append(row.connections).append(if (row.connections == 1) " connection" else " connections")
        if (row.blocked > 0) append(" · ").append(row.blocked).append(" stopped")
        append(" · ").append(if (Signal.SNI in row.signals) "TLS" else "DNS")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(TileShape)
            .toggleable(value = blocked, role = Role.Switch, onValueChange = onBlockChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(
            if (blocked) WardenIcons.Ban else WardenIcons.Sparkle,
            tint = if (blocked) c.blocked else c.guard,
            background = if (blocked) c.blockedSoft else c.guardSoft,
            size = 36.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.provider.displayName, style = Warden.type.label, color = c.text)
            Text(
                row.hosts.sorted().joinToString(", "),
                style = Warden.type.mono.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = c.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(counts, style = Warden.type.caption, color = if (blocked) c.blocked else c.textFaint)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WardenSwitch(blocked)
            Text(if (blocked) "Blocked" else "Allowed", style = Warden.type.caption, color = c.textDim)
        }
    }
}

/** The app's label and icon, loaded off the main thread. Falls back to the package name. */
@Composable
private fun rememberAppInfo(pkg: String): Pair<String, ImageBitmap?> {
    val context = LocalContext.current
    var app by remember(pkg) { mutableStateOf<Pair<String, ImageBitmap?>>(pkg to null) }
    LaunchedEffect(pkg) {
        app = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString() to pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
            }.getOrDefault(pkg to null)
        }
    }
    return app
}
