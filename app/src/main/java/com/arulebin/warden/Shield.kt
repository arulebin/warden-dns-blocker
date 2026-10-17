package com.arulebin.warden

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class GuardState { Off, Arming, On }

/**
 * The signature control: a shield at the center of a radar scope.
 *
 * Armed, a sweep circles the scope and every recent lookup is a blip placed by
 * its domain's hash (so the same domain always lands in the same spot). Blips
 * flare as the sweep crosses them and fade behind it; blocked lookups glow in
 * signal orange. Tap to toggle: the shield squashes on press, a shockwave rolls
 * out when protection engages, and the check mark draws itself in.
 *
 * With system animations removed, the sweep and spinner stop; blips stay static.
 */
@Composable
fun RadarShield(
    state: GuardState,
    lookups: List<QueryLog.Entry>,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Warden.colors
    val reduced = Warden.reducedMotion
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }

    val armed by animateFloatAsState(
        if (state == GuardState.On) 1f else 0f,
        tween(520, easing = FastOutSlowInEasing),
        label = "armed",
    )

    // Ambient loops only exist while they are visible, so an idle, disarmed app does no per-frame work.
    val sweep: State<Float> = if (state == GuardState.On && !reduced) {
        rememberInfiniteTransition(label = "sweep").animateFloat(
            0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "sweepAngle",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val spin: State<Float> = if (state == GuardState.Arming && !reduced) {
        rememberInfiniteTransition(label = "spin").animateFloat(
            0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinAngle",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val check = remember { Animatable(if (state == GuardState.On) 1f else 0f) }
    val shockwave = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == GuardState.On) {
            if (check.value < 1f) {
                if (!reduced) {
                    shockwave.snapTo(0f)
                    coroutineScope {
                        launch { shockwave.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
                        check.animateTo(1f, tween(460, delayMillis = 140, easing = FastOutSlowInEasing))
                    }
                } else {
                    check.snapTo(1f)
                }
            }
        } else {
            check.animateTo(0f, tween(180))
        }
    }

    val pressScale = Modifier.bouncyPress(source, pressedScale = 0.93f)
    val description = when (state) {
        GuardState.On -> "Protection is on"
        GuardState.Arming -> "Turning protection on"
        GuardState.Off -> "Protection is off"
    }

    // Blip geometry is stable per domain, so compute it once per list change, not per frame.
    val blips = remember(lookups) {
        lookups.asSequence().take(36).map { entry ->
            val h = entry.domain.hashCode()
            Blip(
                angle = ((h and 0x7fffffff) % 360).toFloat(),
                radius = 0.40f + ((h ushr 11) and 0x3ff) / 1023f * 0.52f,
                blocked = entry.blocked,
            )
        }.toList()
    }

    Canvas(
        modifier = modifier
            .then(pressScale)
            .toggleable(
                value = state != GuardState.Off,
                interactionSource = source,
                indication = null,
                role = Role.Switch,
                onValueChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
            )
            .semantics {
                contentDescription = "Shield"
                stateDescription = description
            },
    ) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringColor = lerp(c.line, c.guard.copy(alpha = 0.35f), armed)

        drawScopeRings(center, r, ringColor, c.textFaint.copy(alpha = 0.35f + 0.25f * armed))

        // Sweep: a sweep-gradient wedge whose bright edge leads, plus the beam line.
        if (armed > 0f) {
            val angle = sweep.value
            rotate(angle, center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.78f to Color.Transparent,
                        1f to c.guard.copy(alpha = 0.28f * armed),
                        center = center,
                    ),
                    radius = r * 0.97f,
                    center = center,
                )
                drawLine(
                    color = c.guard.copy(alpha = 0.7f * armed),
                    start = center,
                    end = Offset(center.x + r * 0.97f, center.y),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            blips.forEach { b ->
                val behind = ((angle - b.angle) % 360f + 360f) % 360f / 360f
                val glow = if (reduced) 0.6f else (1f - behind).pow(2.2f)
                val a = armed * (0.18f + 0.82f * glow)
                val rad = Math.toRadians(b.angle.toDouble())
                val p = Offset(
                    center.x + (cos(rad) * r * b.radius).toFloat(),
                    center.y + (sin(rad) * r * b.radius).toFloat(),
                )
                val tint = if (b.blocked) c.blocked else c.guard
                val dot = if (b.blocked) 3.5.dp.toPx() else 2.5.dp.toPx()
                if (b.blocked) {
                    drawCircle(tint.copy(alpha = a * 0.25f), radius = dot + 6.dp.toPx() * glow, center = p)
                }
                drawCircle(tint.copy(alpha = if (b.blocked) a else a * 0.6f), radius = dot, center = p)
            }
        }

        // Shockwave on engage.
        val s = shockwave.value
        if (s < 1f) {
            drawCircle(
                color = c.guard.copy(alpha = (1f - s) * 0.55f),
                radius = lerp(r * 0.3f, r * 1.0f, s),
                center = center,
                style = Stroke(width = lerp(6.dp.toPx(), 1.dp.toPx(), s)),
            )
        }

        drawCore(center, r, armed, check.value, state, spin.value, c)
    }
}

private class Blip(val angle: Float, val radius: Float, val blocked: Boolean)

private fun DrawScope.drawScopeRings(center: Offset, r: Float, ring: Color, tick: Color) {
    val hair = 1.dp.toPx()
    drawCircle(ring, radius = r * 0.97f, center = center, style = Stroke(hair))
    drawCircle(ring, radius = r * 0.72f, center = center, style = Stroke(hair))
    drawCircle(ring.copy(alpha = ring.alpha * 0.6f), radius = r * 0.50f, center = center, style = Stroke(hair))

    // Bearing ticks: long every 30°, short every 10°, just inside the outer ring.
    for (deg in 0 until 360 step 10) {
        val major = deg % 30 == 0
        val rad = deg * PI / 180.0
        val outer = r * 0.94f
        val inner = outer - (if (major) 9.dp.toPx() else 4.dp.toPx())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        drawLine(
            color = if (major) tick else tick.copy(alpha = tick.alpha * 0.55f),
            start = Offset(center.x + dx * inner, center.y + dy * inner),
            end = Offset(center.x + dx * outer, center.y + dy * outer),
            strokeWidth = if (major) 1.5.dp.toPx() else 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawCore(
    center: Offset,
    r: Float,
    armed: Float,
    checkProgress: Float,
    state: GuardState,
    spin: Float,
    c: WardenColors,
) {
    val coreR = r * 0.34f

    // Soft halo when armed.
    if (armed > 0f) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(c.guard.copy(alpha = 0.30f * armed), Color.Transparent),
                center = center,
                radius = coreR * 1.7f,
            ),
            radius = coreR * 1.7f,
            center = center,
        )
    }
    drawCircle(c.surface, radius = coreR, center = center)
    drawCircle(lerp(c.line, c.guard, armed), radius = coreR, center = center, style = Stroke(1.5.dp.toPx()))

    if (state == GuardState.Arming) {
        val inset = 5.dp.toPx()
        drawArc(
            color = c.guard,
            startAngle = spin - 90f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(center.x - coreR - inset, center.y - coreR - inset),
            size = Size((coreR + inset) * 2, (coreR + inset) * 2),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
        )
    }

    // Shield silhouette.
    val sh = coreR * 1.08f // height
    val sw = sh * 0.82f // width
    val top = center.y - sh * 0.52f
    val left = center.x - sw / 2f
    val right = center.x + sw / 2f
    val shield = Path().apply {
        moveTo(center.x, top)
        lineTo(right, top + sh * 0.16f)
        lineTo(right, top + sh * 0.50f)
        cubicTo(right, top + sh * 0.78f, center.x + sw * 0.22f, top + sh * 0.94f, center.x, top + sh)
        cubicTo(center.x - sw * 0.22f, top + sh * 0.94f, left, top + sh * 0.78f, left, top + sh * 0.50f)
        lineTo(left, top + sh * 0.16f)
        close()
    }
    drawPath(shield, color = lerp(c.surfaceRaised, c.guard, armed))
    drawPath(
        shield,
        color = lerp(c.textFaint.copy(alpha = 0.5f), c.guard, armed),
        style = Stroke(2.dp.toPx(), join = StrokeJoin.Round),
    )

    val midY = top + sh * 0.50f
    val glyphStroke = Stroke(width = min(5.dp.toPx(), sw * 0.11f), cap = StrokeCap.Round, join = StrokeJoin.Round)

    // Off: a dash that fades out as the shield fills.
    if (armed < 1f) {
        drawLine(
            color = c.textDim.copy(alpha = 1f - armed),
            start = Offset(center.x - sw * 0.18f, midY),
            end = Offset(center.x + sw * 0.18f, midY),
            strokeWidth = glyphStroke.width,
            cap = StrokeCap.Round,
        )
    }

    // On: the check mark draws itself along its path.
    if (checkProgress > 0f) {
        val checkPath = Path().apply {
            moveTo(center.x - sw * 0.20f, midY + sw * 0.01f)
            lineTo(center.x - sw * 0.04f, midY + sw * 0.16f)
            lineTo(center.x + sw * 0.22f, midY - sw * 0.14f)
        }
        val measure = PathMeasure().apply { setPath(checkPath, false) }
        val partial = Path()
        measure.getSegment(0f, measure.length * checkProgress, partial, true)
        drawPath(partial, color = c.onGuard, style = glyphStroke)
    }
}
