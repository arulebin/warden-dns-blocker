package com.arulebin.warden

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** "Night watch" palette — a sentinel's console. */
object Brand {
    val ink = Color(0xFF0C1226)        // deep indigo background
    val surface = Color(0xFF141B33)    // raised panels
    val surfaceHi = Color(0xFF1E2746)  // inputs / chips
    val line = Color(0xFF273156)       // hairlines
    val text = Color(0xFFEAF0FF)       // primary text
    val textDim = Color(0xFF8A97BE)    // secondary text
    val shield = Color(0xFF2FE0C0)     // armed accent (teal)
    val shieldDeep = Color(0xFF17B6E0) // armed accent gradient partner (cyan)
    val alert = Color(0xFFFFB454)      // off-guard (amber)
    val danger = Color(0xFFFF6B6B)     // blocked tag (red)
}

private val WardenColors = darkColorScheme(
    primary = Brand.shield,
    onPrimary = Brand.ink,
    background = Brand.ink,
    onBackground = Brand.text,
    surface = Brand.surface,
    onSurface = Brand.text,
    surfaceVariant = Brand.surfaceHi,
    onSurfaceVariant = Brand.textDim,
    error = Brand.danger,
    outline = Brand.line,
)

// Body stays a clean sans; the distinctive treatments (wordmark tracking,
// monospace data) are applied per-element in the UI where they carry meaning.
private val WardenTypography = Typography(
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
)

@Composable
fun WardenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WardenColors,
        typography = WardenTypography,
        content = content,
    )
}
