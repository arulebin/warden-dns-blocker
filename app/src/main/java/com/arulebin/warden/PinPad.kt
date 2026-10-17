package com.arulebin.warden

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.max

private const val MAX_PIN = 8

/**
 * Full keypad PIN entry. [onSubmit] returns an error message to reject the PIN
 * (the dots shake and clear) or null to accept it.
 */
@Composable
fun PinDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> String?,
    minLength: Int = 4,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = Warden.colors
        val haptics = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val reduced = Warden.reducedMotion

        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var lastError by remember { mutableStateOf("") }
        val shake = remember { Animatable(0f) }
        val appear = remember { Animatable(if (reduced) 1f else 0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, Motion.bouncy()) }

        fun type(digit: Char) {
            if (pin.length >= MAX_PIN) return
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            pin += digit
            error = null
        }

        fun submit() {
            if (pin.length < minLength) return
            val problem = onSubmit(pin) ?: return
            error = problem
            lastError = problem
            pin = ""
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (!reduced) scope.launch { shake.shake() }
        }

        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val s = lerp(0.88f, 1f, appear.value)
                    scaleX = s
                    scaleY = s
                    alpha = appear.value.coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(32.dp))
                .background(c.surface)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconTile(WardenIcons.Lock, tint = c.guard, background = c.guardSoft, size = 48.dp)
            Spacer(Modifier.height(16.dp))
            Text(title, style = Warden.type.title, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = Warden.type.bodySmall, color = c.textDim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))

            Row(
                Modifier
                    .height(20.dp)
                    .graphicsLayer { translationX = shake.value * density }
                    .semantics { contentDescription = "${pin.length} digits entered" },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(max(minLength, pin.length)) { i ->
                    PinDot(filled = i < pin.length, error = error != null)
                }
            }

            Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        lastError,
                        style = Warden.type.caption,
                        color = c.blocked,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("123", "456", "789").forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { d -> DigitKey(d) { type(d) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PadKey(
                        description = "Delete",
                        background = Color.Transparent,
                        enabled = pin.isNotEmpty(),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            pin = pin.dropLast(1)
                        },
                    ) {
                        Icon(WardenIcons.Backspace, contentDescription = null, modifier = Modifier.size(24.dp), tint = c.textDim)
                    }
                    DigitKey('0') { type('0') }
                    val ready = pin.length >= minLength
                    val okBg by animateColorAsState(if (ready) c.guard else c.surfaceRaised, Motion.fade(), label = "okBg")
                    val okFg by animateColorAsState(if (ready) c.onGuard else c.textFaint, Motion.fade(), label = "okFg")
                    PadKey(description = "Confirm", background = okBg, enabled = ready, onClick = ::submit) {
                        Icon(WardenIcons.Check, contentDescription = null, modifier = Modifier.size(24.dp), tint = okFg)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            WardenButton("Cancel", onClick = onDismiss, kind = ButtonKind.Ghost)
        }
    }
}

@Composable
private fun PinDot(filled: Boolean, error: Boolean) {
    val c = Warden.colors
    val scale by animateFloatAsState(if (filled) 1f else 0.7f, Motion.bouncy(), label = "dotScale")
    val color by animateColorAsState(
        when {
            error -> c.blocked
            filled -> c.guard
            else -> c.surfaceRaised
        },
        Motion.fade(160),
        label = "dotColor",
    )
    Box(
        Modifier
            .size(14.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun DigitKey(digit: Char, onClick: () -> Unit) {
    PadKey(description = digit.toString(), background = Warden.colors.surfaceRaised, onClick = onClick) {
        Text(digit.toString(), style = Warden.type.title.copy(fontSize = Warden.type.headline.fontSize), color = Warden.colors.text)
    }
}

@Composable
private fun PadKey(
    description: String,
    background: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(68.dp)
            .bouncyPress(source, 0.88f)
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = source,
                indication = ripple(color = Warden.colors.text),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Runs an action only after the disarm PIN is entered, when a PIN is set.
 * Used for anything that would weaken protection: turning filters off,
 * allowlisting a domain, and so on.
 */
@Stable
class PinGate {
    internal var pending by mutableStateOf<(() -> Unit)?>(null)

    fun run(action: () -> Unit) {
        if (Prefs.lockEnabled && Prefs.hasPin()) pending = action else action()
    }
}

@Composable
fun rememberPinGate(reason: String): PinGate {
    val gate = remember { PinGate() }
    gate.pending?.let { action ->
        PinDialog(
            title = "Enter your PIN",
            subtitle = reason,
            onDismiss = { gate.pending = null },
            onSubmit = { pin ->
                if (Prefs.verifyPin(pin)) {
                    gate.pending = null
                    action()
                    null
                } else {
                    "That PIN isn't right. Try again."
                }
            },
        )
    }
    return gate
}
