package com.cannytvradio.ui

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

object Glass {
    val bg = Color(0xFF0A0A0C)
    val text = Color(0xFFF5F7FC)
    val textDim = text.copy(alpha = 0.66f)
    val textFaint = text.copy(alpha = 0.42f)
    val panel = Color.White.copy(alpha = 0.07f)
    val panelFocused = Color.White.copy(alpha = 0.16f)
    val stroke = Color.White.copy(alpha = 0.10f)
    val bgSheet = Color(0xFF17171B)
    /** Soft white-grey; station colors are washed out towards this. */
    val defaultAccent = Color(0xFFD9DCE3)
}

/** Accent color taken from the current station's logo. */
val LocalAccent = compositionLocalOf { Glass.defaultAccent }

/**
 * A faint tint that follows the time of day: warm sand in the morning, cool in the day,
 * amber in the evening, slate at night. Kept very low so the screen stays calm.
 */
enum class Mood(val tint: Color, val strength: Float) {
    Morning(Color(0xFFE8C9A8), 0.13f),
    Day(Color(0xFFB9CBE0), 0.12f),
    Evening(Color(0xFFE0B48C), 0.13f),
    Night(Color(0xFF8C96B8), 0.10f),
    ;

    companion object {
        fun now(): Mood = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> Morning
            in 11..16 -> Day
            in 17..21 -> Evening
            else -> Night
        }
    }
}

@Composable
fun rememberMood(): Mood {
    val mood by produceState(Mood.now()) {
        while (true) {
            delay(5 * 60_000L)
            value = Mood.now()
        }
    }
    return mood
}

private val type = Typography(
    displayMedium = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
)

@Composable
fun RadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(background = Glass.bg, surface = Glass.bg, onSurface = Glass.text, onBackground = Glass.text),
        typography = type,
        content = content,
    )
}

/**
 * Liquid glass surface without outlines: a dark tint keeps text readable on any background,
 * and a soft sheen from the top gives it depth.
 */
fun Modifier.glass(shape: Shape, tint: Float = 0.55f): Modifier = this
    .clip(shape)
    // Tint and sheen in one gradient, so the surface costs a single draw.
    .background(Brush.verticalGradient(listOf(sheen(tint, 0.10f), sheen(tint, 0.025f))))

/** Dark tint with a little white on top, pre-mixed into one color. */
private fun sheen(tint: Float, white: Float): Color {
    val base = Color(0xFF111114)
    val a = tint + white * (1 - tint)
    fun mix(c: Float) = (c * tint * (1 - white) + 1f * white) / a
    return Color(mix(base.red), mix(base.green), mix(base.blue), a)
}

/** A soft color from the logo, washed out towards grey. */
@Composable
fun rememberAccent(url: String?): Color {
    val context = LocalContext.current
    var color by remember { mutableStateOf(Glass.defaultAccent) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            color = Glass.defaultAccent
            return@LaunchedEffect
        }
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(128).build()
        val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            ?: return@LaunchedEffect
        color = withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap).generate()
            val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.mutedSwatch
            swatch?.let { readable(it.rgb) } ?: Glass.defaultAccent
        }
    }
    return animateColorAsState(color, tween(900), label = "accent").value
}

private fun readable(rgb: Int): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(rgb, hsl)
    // Washed out on purpose: a hint of the station's color, never a loud one.
    if (hsl[1] < 0.15f) return Glass.defaultAccent
    hsl[1] = hsl[1].coerceIn(0.18f, 0.34f)
    hsl[2] = hsl[2].coerceIn(0.72f, 0.80f)
    return Color(ColorUtils.HSLToColor(hsl))
}
