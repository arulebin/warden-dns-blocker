package com.arulebin.warden

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class ThemeMode { System, Light, Dark }

/**
 * "Watchtower" palette: spruce and lichen for the calm, guarded state; signal
 * orange (think safety gear) for anything Warden stopped. Light and dark are
 * designed as a pair — dark uses lighter, desaturated tones, not an inversion.
 */
@Immutable
data class WardenColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val guard: Color,
    val guardSoft: Color,
    val onGuard: Color,
    val blocked: Color,
    val blockedSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val isDark: Boolean,
)

val LightPalette = WardenColors(
    background = Color(0xFFF1F4F0),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE6EBE5),
    line = Color(0xFFD5DDD6),
    text = Color(0xFF12201A),
    textDim = Color(0xFF52615A),
    textFaint = Color(0xFF7F8C85),
    guard = Color(0xFF0E7A5C),
    guardSoft = Color(0xFFD3ECE1),
    onGuard = Color(0xFFFFFFFF),
    blocked = Color(0xFFC4461A),
    blockedSoft = Color(0xFFFBE4D9),
    warn = Color(0xFF7F5300),
    warnSoft = Color(0xFFF7EACB),
    isDark = false,
)

val DarkPalette = WardenColors(
    background = Color(0xFF0A1310),
    surface = Color(0xFF13201B),
    surfaceRaised = Color(0xFF1B2B25),
    line = Color(0xFF253832),
    text = Color(0xFFE3EDE8),
    textDim = Color(0xFF94A79E),
    textFaint = Color(0xFF62756C),
    guard = Color(0xFF5BE3AE),
    guardSoft = Color(0xFF15392C),
    onGuard = Color(0xFF03261A),
    blocked = Color(0xFFFF8A57),
    blockedSoft = Color(0xFF3A1E13),
    warn = Color(0xFFF2B84B),
    warnSoft = Color(0xFF392B0F),
    isDark = true,
)

// --- Type ---------------------------------------------------------------

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w ->
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

val Grotesk = variable(R.font.schibsted_grotesk, 400, 500, 600, 700, 800, 900)
val MonoFamily = variable(R.font.jetbrains_mono, 400, 500, 700)

/** A small, deliberate scale. Numbers use tabular figures so counters don't jitter. */
@Immutable
class WardenType {
    val display = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.Black, fontSize = 34.sp,
        lineHeight = 38.sp, letterSpacing = (-1.0).sp, fontFeatureSettings = "tnum",
    )
    val headline = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp,
        lineHeight = 32.sp, letterSpacing = (-0.7).sp,
    )
    val title = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 20.sp,
        lineHeight = 26.sp, letterSpacing = (-0.3).sp,
    )
    val titleSmall = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        lineHeight = 22.sp, letterSpacing = (-0.1).sp,
    )
    val body = TextStyle(fontFamily = Grotesk, fontSize = 15.sp, lineHeight = 22.sp)
    val bodySmall = TextStyle(fontFamily = Grotesk, fontSize = 13.sp, lineHeight = 19.sp)
    val label = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val caption = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp,
        lineHeight = 16.sp, fontFeatureSettings = "tnum",
    )
    val number = TextStyle(
        fontFamily = Grotesk, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp,
        lineHeight = 28.sp, letterSpacing = (-0.6).sp, fontFeatureSettings = "tnum",
    )
    /** Only for literal technical strings: domains, IPs, URLs. */
    val mono = TextStyle(fontFamily = MonoFamily, fontSize = 14.sp, lineHeight = 20.sp)
}

/** Material's own components (date picker, dialogs) share the app's typeface. */
private val MaterialTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Grotesk),
        displayMedium = displayMedium.copy(fontFamily = Grotesk),
        displaySmall = displaySmall.copy(fontFamily = Grotesk),
        headlineLarge = headlineLarge.copy(fontFamily = Grotesk),
        headlineMedium = headlineMedium.copy(fontFamily = Grotesk),
        headlineSmall = headlineSmall.copy(fontFamily = Grotesk),
        titleLarge = titleLarge.copy(fontFamily = Grotesk),
        titleMedium = titleMedium.copy(fontFamily = Grotesk),
        titleSmall = titleSmall.copy(fontFamily = Grotesk),
        bodyLarge = bodyLarge.copy(fontFamily = Grotesk),
        bodyMedium = bodyMedium.copy(fontFamily = Grotesk),
        bodySmall = bodySmall.copy(fontFamily = Grotesk),
        labelLarge = labelLarge.copy(fontFamily = Grotesk),
        labelMedium = labelMedium.copy(fontFamily = Grotesk),
        labelSmall = labelSmall.copy(fontFamily = Grotesk),
    )
}

// --- Motion -------------------------------------------------------------

/** One shared rhythm for the whole app. Springs for things the user moves, tweens for fades. */
object Motion {
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = 700f)
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 420f)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.58f, stiffness = 320f)
    fun <T> fade(ms: Int = 220): FiniteAnimationSpec<T> = tween(ms, easing = FastOutSlowInEasing)
}

// --- Theme --------------------------------------------------------------

// Non-static: the palette animates during a theme switch, so only readers should recompose.
val LocalWardenColors = compositionLocalOf { LightPalette }
val LocalWardenType = staticCompositionLocalOf { WardenType() }

/** True when the system "Remove animations" setting is on: ambient loops stop. */
val LocalReducedMotion = staticCompositionLocalOf { false }

object Warden {
    val colors: WardenColors
        @Composable @ReadOnlyComposable get() = LocalWardenColors.current
    val type: WardenType
        @Composable @ReadOnlyComposable get() = LocalWardenType.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

@Composable
fun isDarkTheme(mode: ThemeMode = Prefs.themeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun WardenTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduced = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val colors = animatePalette(if (dark) DarkPalette else LightPalette)
    val type = remember { WardenType() }

    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.guard, onPrimary = colors.onGuard, background = colors.background,
            onBackground = colors.text, surface = colors.surface, onSurface = colors.text,
            surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.textDim,
            error = colors.blocked, outline = colors.line,
        )
    } else {
        lightColorScheme(
            primary = colors.guard, onPrimary = colors.onGuard, background = colors.background,
            onBackground = colors.text, surface = colors.surface, onSurface = colors.text,
            surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.textDim,
            error = colors.blocked, outline = colors.line,
        )
    }

    CompositionLocalProvider(
        LocalWardenColors provides colors,
        LocalWardenType provides type,
        LocalReducedMotion provides reduced,
    ) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTypography, content = content)
    }
}

/** Every token animates, so switching theme is one smooth crossfade across the app. */
@Composable
private fun animatePalette(target: WardenColors): WardenColors {
    val spec = tween<Color>(450, easing = FastOutSlowInEasing)

    @Composable
    fun a(c: Color) = animateColorAsState(c, spec, label = "theme").value

    return WardenColors(
        background = a(target.background),
        surface = a(target.surface),
        surfaceRaised = a(target.surfaceRaised),
        line = a(target.line),
        text = a(target.text),
        textDim = a(target.textDim),
        textFaint = a(target.textFaint),
        guard = a(target.guard),
        guardSoft = a(target.guardSoft),
        onGuard = a(target.onGuard),
        blocked = a(target.blocked),
        blockedSoft = a(target.blockedSoft),
        warn = a(target.warn),
        warnSoft = a(target.warnSoft),
        isDark = target.isDark,
    )
}
