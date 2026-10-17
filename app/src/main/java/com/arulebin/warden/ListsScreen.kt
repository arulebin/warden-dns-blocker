package com.arulebin.warden

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ListsScreen(insets: PaddingValues) {
    val c = Warden.colors
    val toaster = LocalToaster.current
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var confirmClearImport by remember { mutableStateOf(false) }
    // Anything that loosens filtering asks for the PIN when a lock is set.
    val gate = rememberPinGate("A PIN is required to loosen your filters.")

    val categoryTotal = Lists.CATEGORIES.sumOf { if (Prefs.isCategoryEnabled(it.key)) Rules.count("cat:${it.key}") else 0 }
    val total = Rules.userBlocked.size + Rules.count("imported") + categoryTotal
    val domains = if (segment == 0) Rules.userBlocked.toList() else Rules.userAllowed.toList()

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
            ScreenTitle("Lists") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RollingNumber(total.toLong(), Warden.type.body.copy(fontFeatureSettings = "tnum"), c.text)
                    Text(if (total == 1) " domain is on the watch list" else " domains are on the watch list", style = Warden.type.body, color = c.textDim)
                }
            }
        }

        item(key = "categories-header") {
            SectionHeader("Categories", supporting = "Ready-made lists you can switch on in one tap.")
        }
        Lists.CATEGORIES.forEach { cat ->
            item(key = "cat-${cat.key}") {
                CategoryCard(cat, gate, Modifier.padding(bottom = 10.dp))
            }
        }

        item(key = "domains-header") {
            SectionHeader("Your domains", supporting = "Rules you add yourself. Subdomains are included.")
        }
        item(key = "domains-segment") {
            Segmented(
                options = listOf("Blocked", "Allowed"),
                selected = segment,
                onSelect = { segment = it },
                icons = listOf(WardenIcons.Ban, WardenIcons.CircleCheck),
                counts = listOf(Rules.userBlocked.size, Rules.userAllowed.size),
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        item(key = "domains-input") {
            Group(Modifier.padding(bottom = 10.dp)) {
                AnimatedContent(
                    targetState = segment,
                    transitionSpec = { fadeIn(Motion.fade(200)) togetherWith fadeOut(Motion.fade(120)) },
                    label = "domainHelp",
                ) { s ->
                    Text(
                        if (s == 0) {
                            "Block a site across every app, for example a game or a distracting feed."
                        } else {
                            "Allowed domains always load, even if a category or your blocklist includes them."
                        },
                        style = Warden.type.bodySmall,
                        color = c.textDim,
                    )
                }
                InputRow(
                    placeholder = "example.com",
                    actionLabel = if (segment == 0) "Block" else "Allow",
                    leadingIcon = WardenIcons.Globe,
                    keyboardType = KeyboardType.Uri,
                    validate = Validate::domain,
                    onSubmit = { raw ->
                        val d = Validate.normalizeDomain(raw)
                        if (segment == 0) {
                            Rules.addBlocked(d)
                            toaster.show("Blocked $d")
                        } else {
                            gate.run {
                                Rules.addAllowed(d)
                                toaster.show("Always allowing $d")
                            }
                        }
                    },
                )
            }
        }

        if (domains.isEmpty()) {
            item(key = "domains-empty-$segment") {
                Group(Modifier.animateItem(), padding = 0.dp) {
                    EmptyState(
                        icon = if (segment == 0) WardenIcons.Ban else WardenIcons.CircleCheck,
                        title = if (segment == 0) "No blocked domains" else "No allowed domains",
                        body = if (segment == 0) {
                            "Add a domain above and Warden will block it everywhere."
                        } else {
                            "Add a domain above to make sure it's never blocked."
                        },
                    )
                }
            }
        } else {
            itemsIndexed(domains, key = { _, d -> "d-$segment-$d" }) { i, d ->
                Column(Modifier.animateItem().groupItem(i, domains.size)) {
                    if (i > 0) {
                        Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(1.dp).background(c.line.copy(alpha = 0.6f)))
                    }
                    DomainRow(
                        domain = d,
                        accent = if (segment == 0) c.blocked else c.guard,
                        accentSoft = if (segment == 0) c.blockedSoft else c.guardSoft,
                        onRemove = {
                            if (segment == 0) {
                                gate.run {
                                    Rules.removeBlocked(d)
                                    toaster.show("Unblocked $d", "Undo") { Rules.addBlocked(d) }
                                }
                            } else {
                                Rules.removeAllowed(d)
                                toaster.show("Stopped allowing $d", "Undo") { Rules.addAllowed(d) }
                            }
                        },
                    )
                }
            }
        }

        item(key = "import-header") {
            SectionHeader("Import a list", supporting = "Paste a link to a hosts file to add thousands of domains at once.")
        }
        item(key = "import") {
            val imported = Rules.count("imported")
            val busy = Lists.busy["imported"] == true
            val importError = Lists.lastError["imported"]
            Group {
                InputRow(
                    placeholder = "https://…/hosts",
                    actionLabel = "Import",
                    leadingIcon = WardenIcons.Link,
                    keyboardType = KeyboardType.Uri,
                    validate = Validate::url,
                    busy = busy,
                    onSubmit = { Lists.importFromUrl(it.trim()) },
                )
                AnimatedVisibility(
                    visible = importError != null,
                    enter = expandVertically(Motion.settle()) + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    InlineMessage(
                        "Couldn't import that list (${importError ?: ""}). Check the link and try again.",
                        c.blocked,
                    )
                }
                AnimatedVisibility(
                    visible = imported > 0,
                    enter = expandVertically(Motion.settle()) + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(WardenIcons.Lists, tint = c.guard, background = c.guardSoft, size = 36.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            RollingNumber(imported.toLong(), Warden.type.titleSmall.copy(fontFeatureSettings = "tnum"), c.text)
                            Text("domains imported", style = Warden.type.caption, color = c.textDim)
                        }
                        WardenButton("Remove", onClick = { gate.run { confirmClearImport = true } }, kind = ButtonKind.Danger)
                    }
                }
            }
        }
    }

    if (confirmClearImport) {
        ConfirmDialog(
            title = "Remove imported domains?",
            body = "Warden will stop blocking the ${formatCount(Rules.count("imported").toLong())} domains from your imported list. You can import it again later.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                Lists.clearImported()
                confirmClearImport = false
                toaster.show("Removed imported domains")
            },
            onDismiss = { confirmClearImport = false },
        )
    }
}

@Composable
private fun CategoryCard(cat: Lists.Category, gate: PinGate, modifier: Modifier = Modifier) {
    val c = Warden.colors
    val enabled = Prefs.isCategoryEnabled(cat.key)
    val source = "cat:${cat.key}"
    val count = Rules.count(source)
    val busy = Lists.busy[source] == true
    val error = Lists.lastError[source]
    val icon = when (cat.key) {
        "ads" -> WardenIcons.Megaphone
        "adult" -> WardenIcons.EyeOff
        "social" -> WardenIcons.Users
        else -> WardenIcons.Lists
    }
    val tileBg by animateColorAsState(if (enabled) c.guardSoft else c.surfaceRaised, Motion.fade(), label = "catTile")
    val tileFg by animateColorAsState(if (enabled) c.guard else c.textDim, Motion.fade(), label = "catIcon")

    Column(
        modifier
            .fillMaxWidth()
            .clip(GroupShape)
            .background(c.surface)
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = { on ->
                    if (on) Lists.enableCategory(cat.key) else gate.run { Lists.disableCategory(cat.key) }
                },
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile(icon, tint = tileFg, background = tileBg, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(cat.title, style = Warden.type.titleSmall, color = c.text)
                Text(cat.desc, style = Warden.type.bodySmall, color = c.textDim)
                if (!enabled && cat.downloadHint != null) {
                    Text("${cat.downloadHint} download", style = Warden.type.caption, color = c.textFaint)
                }
            }
            WardenSwitch(enabled)
        }

        val status = when {
            busy -> CategoryStatus.Busy
            error != null && enabled -> CategoryStatus.Failed
            enabled && count > 0 -> CategoryStatus.Ready
            else -> null
        }
        AnimatedVisibility(
            visible = status != null,
            enter = expandVertically(Motion.settle()) + fadeIn(),
            exit = shrinkVertically(Motion.fade(180)) + fadeOut(Motion.fade(120)),
        ) {
            AnimatedContent(
                targetState = status,
                transitionSpec = { fadeIn(Motion.fade(200)) togetherWith fadeOut(Motion.fade(120)) },
                label = "catStatus",
            ) { s ->
                when (s) {
                    CategoryStatus.Busy -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Downloading list…", style = Warden.type.caption, color = c.textDim)
                        IndeterminateBar()
                    }
                    CategoryStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineMessage("Download failed: $error", c.blocked, Modifier.weight(1f))
                        WardenButton("Retry", onClick = { Lists.refreshCategory(cat.key) }, kind = ButtonKind.Ghost)
                    }
                    CategoryStatus.Ready -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.width(6.dp).height(6.dp).clip(CircleShape).background(c.guard))
                        RollingNumber(count.toLong(), Warden.type.caption, c.text)
                        Text("domains blocked", style = Warden.type.caption, color = c.textDim)
                    }
                    null -> Box(Modifier)
                }
            }
        }
    }
}

private enum class CategoryStatus { Busy, Failed, Ready }

/** A slim bar with a segment sliding across it. Static when animations are off. */
@Composable
fun IndeterminateBar() {
    val c = Warden.colors
    val reduced = Warden.reducedMotion
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.surfaceRaised),
    ) {
        val segment = maxWidth * 0.35f
        val travel = maxWidth + segment
        val progress = if (reduced) {
            0.5f
        } else {
            val t = rememberInfiniteTransition(label = "download")
            val p by t.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
                label = "downloadProgress",
            )
            p
        }
        Box(
            Modifier
                .offset(x = travel * progress - segment)
                .width(segment)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(c.guard),
        )
    }
}
