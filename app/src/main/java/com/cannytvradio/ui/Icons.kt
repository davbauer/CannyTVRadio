package com.cannytvradio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * One consistent icon set: thin rounded strokes (Lucide style, 24x24),
 * filled shapes only where a solid button reads better (play, pause, skip, heart).
 */
object Ico {
    val Home = line(
        "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
        "M3 10a2 2 0 0 1 .7-1.53l7-6a2 2 0 0 1 2.6 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    )
    val Search = line(circle(11f, 11f, 7.5f), "M20.5 20.5l-4.2-4.2")
    val Globe = line(circle(12f, 12f, 9.5f), "M12 2.5a14 14 0 0 0 0 19a14 14 0 0 0 0-19", "M2.5 12h19")
    val Heart = line(HEART)
    val HeartFilled = fill(HEART)
    val Sliders = line(
        "M21 5h-7", "M10 5H3", "M14 3v4",
        "M21 12h-9", "M8 12H3", "M8 10v4",
        "M21 19h-5", "M12 19H3", "M16 17v4",
    )
    val Moon = line("M12 3a6 6 0 0 0 9 9a9 9 0 1 1-9-9z")
    val Timer = line(circle(12f, 14f, 8f), "M10 2h4", "M12 14l3-3")
    val Mic = line(
        "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z",
        "M19 10v2a7 7 0 0 1-14 0v-2",
        "M12 19v3",
    )
    val Expand = line("M15 3h6v6", "M9 21H3v-6", "M21 3l-7 7", "M3 21l7-7")
    val Music = line("M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f))
    val Waves = line("M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3")
    val Pin = line("M20 10c0 4.99-5.54 10.19-7.4 11.8a1 1 0 0 1-1.2 0C9.54 20.19 4 14.99 4 10a8 8 0 0 1 16 0", circle(12f, 10f, 3f))
    val Link = line(circle(12f, 12f, 9.5f), "M2.5 12h19")
    val ThumbUp = line(
        "M7 10v12",
        "M15 5.88L14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88z",
    )
    val Move = line("M5 9l-3 3l3 3", "M9 5l3-3l3 3", "M15 19l-3 3l-3-3", "M19 9l3 3l-3 3", "M2 12h20", "M12 2v20")
    val ChevronLeft = line("M15 18l-6-6l6-6")
    val ChevronRight = line("M9 18l6-6l-6-6")
    val Trending = line("M22 7l-8.5 8.5l-5-5L2 17", "M16 7h6v6")

    val Play = fill("M7 4.6v14.8a1.1 1.1 0 0 0 1.66.95l12.3-7.4a1.1 1.1 0 0 0 0-1.9L8.66 3.65A1.1 1.1 0 0 0 7 4.6z")
    val Pause = fill(
        "M7 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
        "M15 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
    )
    val Next = fill(
        "M5 5.6v12.8a1 1 0 0 0 1.56.83l9.4-6.4a1 1 0 0 0 0-1.66l-9.4-6.4A1 1 0 0 0 5 5.6z",
        "M18.2 5h.6a.8.8 0 0 1 .8.8v12.4a.8.8 0 0 1-.8.8h-.6a.8.8 0 0 1-.8-.8V5.8a.8.8 0 0 1 .8-.8z",
    )
    val Previous = fill(
        "M19 5.6v12.8a1 1 0 0 1-1.56.83l-9.4-6.4a1 1 0 0 1 0-1.66l9.4-6.4A1 1 0 0 1 19 5.6z",
        "M5.2 5h.6a.8.8 0 0 1 .8.8v12.4a.8.8 0 0 1-.8.8h-.6a.8.8 0 0 1-.8-.8V5.8a.8.8 0 0 1 .8-.8z",
    )

    /** The app's mark: a dot sending out waves. */
    val Broadcast = ImageVector.Builder("broadcast", 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(circle(12f, 12f, 2.2f)), fill = SolidColor(Color.White))
        .addPath(
            addPathNodes("M8.2 8.2a5.4 5.4 0 0 0 0 7.6M15.8 8.2a5.4 5.4 0 0 1 0 7.6M5.1 5.1a9.8 9.8 0 0 0 0 13.8M18.9 5.1a9.8 9.8 0 0 1 0 13.8"),
            stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
        )
        .build()
}

private const val HEART =
    "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2c-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7z"

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

private fun line(vararg paths: String): ImageVector {
    val b = ImageVector.Builder("i", 24.dp, 24.dp, 24f, 24f)
    paths.forEach {
        b.addPath(
            addPathNodes(it),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

private fun fill(vararg paths: String): ImageVector {
    val b = ImageVector.Builder("i", 24.dp, 24.dp, 24f, 24f)
    paths.forEach { b.addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    return b.build()
}
