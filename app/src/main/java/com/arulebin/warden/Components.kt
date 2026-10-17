package com.arulebin.warden

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

// --- Shapes -------------------------------------------------------------
// Radius follows hierarchy: groups are softest, controls tighter, chips fully round.

val GroupShape = RoundedCornerShape(24.dp)
val ControlShape = RoundedCornerShape(16.dp)
val TileShape = RoundedCornerShape(12.dp)

// --- Interaction --------------------------------------------------------

/** Springy scale-down while pressed. Transform only, so layout never shifts. */
fun Modifier.bouncyPress(source: InteractionSource, pressedScale: Float = 0.96f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, Motion.press(), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A short decaying horizontal shake: the "no" gesture for invalid input. */
suspend fun Animatable<Float, AnimationVector1D>.shake() {
    animateTo(
        0f,
        keyframes {
            durationMillis = 380
            -14f at 50
            12f at 110
            -8f at 180
            5f at 250
            -2f at 320
        },
    )
}

// --- Layout -------------------------------------------------------------

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, subtitle: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = Warden.type.headline,
            color = Warden.colors.text,
            modifier = Modifier.semantics { heading() },
        )
        subtitle?.invoke()
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = Warden.type.title,
                color = Warden.colors.text,
                modifier = Modifier.semantics { heading() },
            )
            supporting?.let { Text(it, style = Warden.type.bodySmall, color = Warden.colors.textDim) }
        }
        trailing?.invoke()
    }
}

/** A surface that groups related controls. No border, no shadow: tone does the separating. */
@Composable
fun Group(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    spacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(GroupShape)
            .background(Warden.colors.surface)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * Lets separate LazyColumn items read as one grouped surface: only the first
 * and last rows get rounded outer corners.
 */
@Composable
fun Modifier.groupItem(index: Int, count: Int): Modifier {
    val top = if (index == 0) 24.dp else 0.dp
    val bottom = if (index == count - 1) 24.dp else 0.dp
    return this
        .fillMaxWidth()
        .clip(RoundedCornerShape(top, top, bottom, bottom))
        .background(Warden.colors.surface)
}

@Composable
fun IconTile(icon: ImageVector, tint: Color, background: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier.size(size).clip(TileShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.5f), tint = tint)
    }
}

// --- Buttons ------------------------------------------------------------

enum class ButtonKind { Primary, Tonal, Danger, Ghost }

@Composable
fun WardenButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = Warden.colors
    val source = remember { MutableInteractionSource() }
    val (bg, fg) = when (kind) {
        ButtonKind.Primary -> c.guard to c.onGuard
        ButtonKind.Tonal -> c.guardSoft to c.text
        ButtonKind.Danger -> c.blockedSoft to c.blocked
        ButtonKind.Ghost -> Color.Transparent to c.guard
    }
    val disabledBg = if (kind == ButtonKind.Ghost) Color.Transparent else c.surfaceRaised
    val container by animateColorAsState(if (enabled) bg else disabledBg, Motion.fade(), label = "buttonBg")
    val content by animateColorAsState(if (enabled) fg else c.textFaint, Motion.fade(), label = "buttonFg")

    Box(
        modifier
            .heightIn(min = 48.dp)
            .bouncyPress(source)
            .clip(ControlShape)
            .background(container)
            .clickable(
                interactionSource = source,
                indication = ripple(color = content),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (kind == ButtonKind.Ghost) 12.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = {
                (fadeIn(Motion.fade(160)) + scaleIn(Motion.bouncy(), initialScale = 0.7f)) togetherWith
                    fadeOut(Motion.fade(100))
            },
            label = "buttonLoading",
        ) { busy ->
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = content, strokeWidth = 2.dp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp), tint = content) }
                    Text(text, style = Warden.type.label, color = content, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Warden.colors.textDim,
    background: Color = Color.Transparent,
    size: Dp = 40.dp,
) {
    val source = remember { MutableInteractionSource() }
    // Outer box guarantees a 48dp touch target even when the visual is smaller.
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .bouncyPress(source, 0.86f)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(background)
                .indication(source, ripple(color = tint)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
        }
    }
}

// --- Inputs -------------------------------------------------------------

@Composable
fun WardenTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    mono: Boolean = true,
    leadingIcon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    val c = Warden.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val border by animateColorAsState(
        when {
            isError -> c.blocked
            focused -> c.guard
            else -> Color.Transparent
        },
        Motion.fade(),
        label = "fieldBorder",
    )
    val iconTint by animateColorAsState(if (focused) c.guard else c.textFaint, Motion.fade(), label = "fieldIcon")
    val style = (if (mono) Warden.type.mono else Warden.type.body).copy(color = c.text)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(c.guard),
        interactionSource = source,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(ControlShape)
                    .background(c.surfaceRaised)
                    .border(1.5.dp, border, ControlShape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                leadingIcon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp), tint = iconTint) }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = style, color = c.textFaint, maxLines = 1)
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * Field + action button. Validates on submit (not per keystroke); a bad value
 * shakes the row and explains the fix right below it.
 */
@Composable
fun InputRow(
    placeholder: String,
    actionLabel: String,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    validate: (String) -> String? = { null },
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingIcon: ImageVector? = null,
    busy: Boolean = false,
) {
    var value by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf("") }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reduced = Warden.reducedMotion

    val submit: () -> Unit = {
        val problem = validate(value)
        if (problem != null) {
            error = problem
            lastError = problem
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (!reduced) scope.launch { offset.shake() }
        } else {
            onSubmit(value.trim())
            value = ""
            error = null
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().graphicsLayer { translationX = offset.value * density },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WardenTextField(
                value = value,
                onValueChange = { value = it; error = null },
                placeholder = placeholder,
                modifier = Modifier.weight(1f),
                isError = error != null,
                leadingIcon = leadingIcon,
                keyboardType = keyboardType,
                onDone = { if (value.isNotBlank() && !busy) submit() },
            )
            WardenButton(actionLabel, onClick = submit, enabled = value.isNotBlank(), loading = busy)
        }
        AnimatedVisibility(
            visible = error != null,
            enter = expandVertically(Motion.settle()) + fadeIn(Motion.fade()),
            exit = shrinkVertically(Motion.fade(160)) + fadeOut(Motion.fade(120)),
        ) {
            InlineMessage(lastError, Warden.colors.blocked)
        }
    }
}

@Composable
fun InlineMessage(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector = WardenIcons.Alert) {
    Row(
        modifier.padding(start = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        Text(text, style = Warden.type.caption, color = color)
    }
}

object Validate {
    private val domainPattern =
        Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{1,62}$")

    fun normalizeDomain(raw: String) = raw.lowercase().trim()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .substringBefore('/').trimEnd('.')

    fun domain(raw: String): String? =
        if (domainPattern.matches(normalizeDomain(raw))) null else "Enter a domain like example.com"

    fun ipv4(raw: String): String? {
        val parts = raw.trim().split('.')
        val ok = parts.size == 4 && parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255
        }
        return if (ok) null else "Enter an IPv4 address like 1.1.1.1"
    }

    fun url(raw: String): String? {
        val t = raw.trim()
        val ok = (t.startsWith("https://") || t.startsWith("http://")) && t.length > 12 && ' ' !in t
        return if (ok) null else "Enter a full link that starts with https://"
    }
}

// --- Selection controls -------------------------------------------------

/** Segmented control whose selection pill slides between options on a spring. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector>? = null,
    counts: List<Int>? = null,
) {
    val c = Warden.colors
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(c.surfaceRaised)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        val x by animateDpAsState(segment * selected, Motion.settle(), label = "segment")
        Box(
            Modifier
                .offset(x = x)
                .width(segment)
                .height(44.dp)
                .shadow(if (c.isDark) 0.dp else 2.dp, TileShape)
                .clip(TileShape)
                .background(if (c.isDark) c.line else c.surface),
        )
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEachIndexed { i, label ->
                val isSelected = i == selected
                val fg by animateColorAsState(if (isSelected) c.text else c.textDim, Motion.fade(), label = "segmentFg")
                Row(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(TileShape)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(i) }),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icons?.getOrNull(i)?.let {
                        Icon(it, contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(label, style = Warden.type.label, color = fg, maxLines = 1)
                    counts?.getOrNull(i)?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            formatCount(it.toLong()),
                            style = Warden.type.caption,
                            color = if (isSelected) c.guard else c.textFaint,
                        )
                    }
                }
            }
        }
    }
}

/** Visual-only switch; the parent row owns the toggleable semantics and touch target. */
@Composable
fun WardenSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Warden.colors
    val track by animateColorAsState(if (checked) c.guard else c.surfaceRaised, Motion.fade(), label = "track")
    val outline by animateColorAsState(if (checked) Color.Transparent else c.line, Motion.fade(), label = "outline")
    val thumb by animateColorAsState(if (checked) c.onGuard else c.textFaint, Motion.fade(), label = "thumb")
    val center by animateDpAsState(if (checked) 36.dp else 16.dp, Motion.bouncy(), label = "thumbX")
    val diameter by animateDpAsState(if (checked) 24.dp else 16.dp, Motion.bouncy(), label = "thumbSize")

    Box(
        modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.5.dp, outline, CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = center - diameter / 2)
                .size(diameter)
                .clip(CircleShape)
                .background(thumb),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = checked,
                enter = scaleIn(Motion.bouncy()) + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                Icon(WardenIcons.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = track)
            }
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(TileShape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Warden.type.titleSmall, color = Warden.colors.text)
            supporting?.let { Text(it, style = Warden.type.bodySmall, color = Warden.colors.textDim) }
        }
        WardenSwitch(checked)
    }
}

// --- Numbers ------------------------------------------------------------

fun formatCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)

/**
 * A counter whose digits roll like an odometer: up when the value grows, down
 * when it shrinks. Only the digits that changed move.
 */
@Composable
fun RollingNumber(
    value: Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    suffix: String = "",
) {
    val text = formatCount(value) + suffix
    if (Warden.reducedMotion) {
        Text(text, style = style, color = color, modifier = modifier)
        return
    }
    val previous = remember { longArrayOf(value) }
    val rising = value >= previous[0]
    SideEffect { previous[0] = value }

    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            // Key from the right so the ones digit stays the ones digit as the number grows.
            key(text.length - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val dir = if (rising) 1 else -1
                        (slideInVertically(Motion.settle()) { it * dir } + fadeIn(Motion.fade(160))) togetherWith
                            (slideOutVertically(Motion.settle()) { -it * dir } + fadeOut(Motion.fade(120))) using
                            SizeTransform(clip = true)
                    },
                    label = "digit",
                ) { digit ->
                    Text(digit.toString(), style = style, color = color)
                }
            }
        }
    }
}

// --- Rows & states ------------------------------------------------------

@Composable
fun DomainRow(
    domain: String,
    accent: Color,
    accentSoft: Color,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(TileShape).background(accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(domain.first().uppercase(), style = Warden.type.label, color = accent)
        }
        Text(
            domain,
            style = Warden.type.mono,
            color = Warden.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconAction(WardenIcons.Close, "Remove $domain", onRemove, size = 36.dp)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconTile(icon, Warden.colors.textDim, Warden.colors.surfaceRaised, size = 48.dp)
        Spacer(Modifier.height(4.dp))
        Text(title, style = Warden.type.titleSmall, color = Warden.colors.text, textAlign = TextAlign.Center)
        Text(body, style = Warden.type.bodySmall, color = Warden.colors.textDim, textAlign = TextAlign.Center)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val c = Warden.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = Warden.type.title, color = c.text) },
        text = { Text(body, style = Warden.type.body, color = c.textDim) },
        confirmButton = {
            WardenButton(
                confirmLabel,
                onClick = onConfirm,
                kind = if (destructive) ButtonKind.Danger else ButtonKind.Primary,
            )
        },
        dismissButton = { WardenButton("Cancel", onClick = onDismiss, kind = ButtonKind.Ghost) },
    )
}

// --- Toasts -------------------------------------------------------------

@Stable
class ToastState {
    class Toast(val id: Long, val message: String, val actionLabel: String?, val action: (() -> Unit)?)

    var current by mutableStateOf<Toast?>(null)
        private set
    private var nextId = 0L

    fun show(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        current = Toast(nextId++, message, actionLabel, action)
    }

    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

val LocalToaster = staticCompositionLocalOf { ToastState() }

@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    val toast = state.current
    if (toast != null) {
        LaunchedEffect(toast.id) {
            delay(if (toast.action != null) 4500 else 2800)
            state.dismiss(toast.id)
        }
    }
    AnimatedContent(
        targetState = toast,
        modifier = modifier.fillMaxWidth(),
        contentKey = { it?.id },
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            (slideInVertically(Motion.bouncy()) { it } + fadeIn(Motion.fade()) +
                scaleIn(Motion.bouncy(), initialScale = 0.9f)) togetherWith
                (fadeOut(Motion.fade(120)) + scaleOut(Motion.fade(120), targetScale = 0.94f)) using
                SizeTransform(clip = false)
        },
        label = "toast",
    ) { t ->
        if (t == null) {
            Spacer(Modifier.fillMaxWidth())
        } else {
            ToastPill(t) { state.dismiss(t.id) }
        }
    }
}

@Composable
private fun ToastPill(toast: ToastState.Toast, onDismiss: () -> Unit) {
    val c = Warden.colors
    // Inverted surface so the toast reads clearly over any content; the action
    // borrows the opposite theme's accent to keep contrast.
    val actionColor = if (c.isDark) LightPalette.guard else DarkPalette.guard
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .shadow(16.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(c.text)
                .padding(start = 20.dp, end = 6.dp)
                .heightIn(min = 52.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                toast.message,
                style = Warden.type.label,
                color = c.background,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = if (toast.action == null) 14.dp else 0.dp),
            )
            if (toast.actionLabel != null && toast.action != null) {
                Box(
                    Modifier
                        .heightIn(min = 44.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { toast.action.invoke(); onDismiss() }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(toast.actionLabel, style = Warden.type.label, color = actionColor)
                }
            }
        }
    }
}
