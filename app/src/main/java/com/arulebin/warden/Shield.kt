package com.arulebin.warden

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * The signature control: a shield you arm. Tap to toggle protection. Armed =
 * cyan-gradient fill, a check glyph, and a slow "vigilance" pulse ring; off =
 * dim slate outline with a dash. The pulse respects the system's
 * remove-animations setting.
 */
@Composable
fun ShieldControl(
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animationsEnabled = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }

    val transition = rememberInfiniteTransition(label = "shield")
    val pulseRaw by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val pulse = if (on && animationsEnabled) pulseRaw else 0f

    val desc = if (on) "Protection on. Activate to turn off." else "Protection off. Activate to turn on."

    Canvas(
        modifier = modifier
            .size(208.dp)
            .toggleable(
                value = on,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .semantics { contentDescription = desc },
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        if (on) {
            // expanding "vigilance" ring
            drawCircle(
                color = Brand.shield.copy(alpha = (1f - pulse) * 0.30f),
                radius = lerp(w * 0.30f, w * 0.50f, pulse),
                center = Offset(cx, cy),
            )
            // soft ambient halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Brand.shield.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = w * 0.52f,
                ),
                radius = w * 0.52f,
                center = Offset(cx, cy),
            )
        }

        // shield silhouette
        val pad = w * 0.20f
        val left = pad
        val right = w - pad
        val top = h * 0.16f
        val bottom = h * 0.86f
        val sw = right - left
        val shield = Path().apply {
            moveTo(cx, top)
            lineTo(right, top + sw * 0.22f)
            lineTo(right, top + sw * 0.58f)
            quadraticBezierTo(right, bottom - sw * 0.06f, cx, bottom)
            quadraticBezierTo(left, bottom - sw * 0.06f, left, top + sw * 0.58f)
            lineTo(left, top + sw * 0.22f)
            close()
        }

        if (on) {
            drawPath(shield, brush = Brush.verticalGradient(listOf(Brand.shield, Brand.shieldDeep)))
        } else {
            drawPath(shield, color = Brand.surfaceHi)
        }
        drawPath(
            shield,
            color = if (on) Brand.shield else Brand.line,
            style = Stroke(width = 3.dp.toPx()),
        )

        // center glyph: check when armed, dash when off
        val midY = (top + bottom) / 2f
        if (on) {
            val check = Path().apply {
                moveTo(cx - sw * 0.17f, midY + sw * 0.02f)
                lineTo(cx - sw * 0.02f, midY + sw * 0.15f)
                lineTo(cx + sw * 0.21f, midY - sw * 0.14f)
            }
            drawPath(
                check,
                color = Brand.ink,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        } else {
            drawLine(
                color = Brand.textDim,
                start = Offset(cx - sw * 0.16f, midY),
                end = Offset(cx + sw * 0.16f, midY),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
