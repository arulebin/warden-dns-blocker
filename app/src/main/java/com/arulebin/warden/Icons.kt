package com.arulebin.warden

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * One stroke icon family (24-unit grid, 2-unit round strokes) so every glyph in
 * the app shares weight and terminals. Tinted at use via Icon(tint = …).
 */
object WardenIcons {
    val Shield = icon("M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z")
    val ShieldCheck = icon("M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z", "M9 12l2 2 4-4")
    val Lists = icon("M9 6h12", "M9 12h12", "M9 18h12", "M4 6h.01", "M4 12h.01", "M4 18h.01")
    val Sliders = icon(
        "M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3",
        "M1 14h6", "M9 8h6", "M17 16h6",
    )
    val Sun = icon(
        "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z",
        "M12 2v2", "M12 20v2", "M4.93 4.93l1.41 1.41", "M17.66 17.66l1.41 1.41",
        "M2 12h2", "M20 12h2", "M6.34 17.66l-1.41 1.41", "M19.07 4.93l-1.41 1.41",
    )
    val Moon = icon("M12 3a6 6 0 0 0 9 9a9 9 0 1 1-9-9z")
    val Monitor = icon("M3 4h18v12H3z", "M8 20h8", "M12 16v4")
    val Plus = icon("M12 5v14", "M5 12h14")
    val Minus = icon("M5 12h14")
    val Close = icon("M18 6L6 18", "M6 6l12 12")
    val Check = icon("M20 6L9 17l-5-5")
    val Lock = icon("M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4")
    val Globe = icon(
        "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M2 12h20",
        "M12 2a15.3 15.3 0 0 1 4 10a15.3 15.3 0 0 1-4 10a15.3 15.3 0 0 1-4-10a15.3 15.3 0 0 1 4-10z",
    )
    val Alert = icon("M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z", "M12 9v4", "M12 17h.01")
    val Ban = icon("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M4.93 4.93l14.14 14.14")
    val CircleCheck = icon("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M8.5 12l2.5 2.5 4.5-5")
    val Trash = icon("M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    val Clock = icon("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M12 6v6l4 2")
    val Link = icon(
        "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
        "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
    )
    val Megaphone = icon("M3 11l18-5v12L3 14v-3z", "M11.6 16.8a3 3 0 1 1-5.8-1.6")
    val EyeOff = icon(
        "M9.88 9.88a3 3 0 1 0 4.24 4.24",
        "M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68",
        "M6.61 6.61A13.5 13.5 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61",
        "M2 2l20 20",
    )
    val Users = icon(
        "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M9 3a4 4 0 1 0 0 8a4 4 0 1 0 0-8z",
        "M22 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75",
    )
    val Backspace = icon("M21 4H8l-7 8 7 8h13a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2z", "M18 9l-6 6", "M12 9l6 6")
    val Activity = icon("M22 12h-4l-3 9L9 3l-3 9H2")
    val ChevronDown = icon("M6 9l6 6 6-6")
    val Target = icon(
        "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z",
        "M12 6a6 6 0 1 0 0 12a6 6 0 1 0 0-12z",
        "M12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4z",
    )
    val Flame = icon(
        "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3c-1.07-2.14-.22-4.05 2-6c.5 2.5 2 4.9 4 6.5c2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.15.43-2.29 1-3a2.5 2.5 0 0 0 2.5 2.5z",
    )
    val Calendar = icon("M4 5h16v16H4z", "M16 3v4", "M8 3v4", "M4 11h16")
    val Search = icon("M11 3a8 8 0 1 0 0 16a8 8 0 1 0 0-16z", "M21 21l-4.35-4.35")
    val Refresh = icon("M3 12a9 9 0 1 0 9-9a9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    val Trophy = icon(
        "M6 9H4.5a2.5 2.5 0 0 1 0-5H6", "M18 9h1.5a2.5 2.5 0 0 0 0-5H18",
        "M4 22h16", "M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22",
        "M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22",
        "M18 2H6v7a6 6 0 0 0 12 0V2z",
    )
}

private fun icon(vararg paths: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
