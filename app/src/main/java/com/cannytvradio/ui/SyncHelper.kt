package com.cannytvradio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cannytvradio.R
import com.cannytvradio.RadioApp
import com.cannytvradio.data.SaverReact
import com.cannytvradio.data.SaverVisual
import com.cannytvradio.player.BeatTracker
import com.cannytvradio.player.SYNC_BEAT_MS
import com.cannytvradio.player.SYNC_FIRST_BEAT_MS
import com.cannytvradio.player.SoundProcessor
import com.cannytvradio.player.syncPlayer
import kotlin.math.abs
import kotlin.math.roundToLong

/** How far the music sync can shift the light, in milliseconds either way. */
internal const val SYNC_RANGE = 400

/** "0 ms", "+40 ms", "-30 ms". */
internal fun syncText(ms: Int) = if (ms == 0) "0 ms" else "%+d ms".format(ms)

/** The backgrounds the helper can show with the knocks, after the circle. */
private val PREVIEW_VISUALS = listOf(SaverVisual.Starfield, SaverVisual.Aurora)

/** Last beat of the click track; after it the track starts over. */
private const val SYNC_LAST_BEAT_MS = 29_500L

/**
 * Lines up the screensaver's light with the sound on this TV. It plays knocking sounds through
 * the same audio path and beat detection as the radio, and a circle lights up on every knock the
 * detection finds. By itself it checks that every knock is found and how late the detection is;
 * by ear and eye you shift the light earlier or later until it flashes exactly with the knock.
 */
@Composable
fun SyncHelper(app: RadioApp, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val refresh = remember { (view.display?.refreshRate ?: 60f).toInt().coerceIn(30, 240) }
    val start = remember { app.settings.prefs.value }
    var offset by remember { mutableIntStateOf(start.saverSyncMs) }
    val sound = remember { SoundProcessor().apply { normalize = false } }
    val player = remember { syncPlayer(context, sound) }
    val radioWasPlaying = remember { app.player.player.isPlaying }
    val ui = LocalUi.current
    val ps by app.player.state.collectAsState()
    // Up and down go through the views: the circle, then each background with the knocks.
    var screen by remember { mutableIntStateOf(0) }
    val previewVisual = PREVIEW_VISUALS.getOrNull(screen - 1)
    val preview = previewVisual != null
    LaunchedEffect(preview) { ui.syncPreview = preview }
    DisposableEffect(Unit) {
        if (radioWasPlaying) app.player.pause()
        onDispose {
            ui.syncPreview = false
            player.release()
            if (radioWasPlaying) app.player.resume()
        }
    }

    val flash = remember { mutableFloatStateOf(0f) }
    var found by remember { mutableIntStateOf(0) }
    var expected by remember { mutableIntStateOf(0) }
    var spread by remember { mutableIntStateOf(0) }
    // How late the detection is against the true knock times, from the recent knocks.
    val errors = remember { ArrayList<Long>() }
    var lag by remember { mutableIntStateOf(start.saverDetectMs) }

    LaunchedEffect(Unit) {
        val out = FloatArray(BeatTracker.OUT_SIZE)
        var seen = sound.onsetTotal
        var nextBeat = 0
        var lastPos = 0L
        while (true) {
            withFrameNanos {
                val pos = player.currentPosition
                if (pos + 1000 < lastPos) nextBeat = 0 // the track started over
                lastPos = pos
                if (!player.isPlaying) return@withFrameNanos
                // Exactly the screensaver's formula, so the value set here holds there too.
                sound.pulse(pos + visualLeadMs(refresh, refresh) + lag - offset, out)
                flash.floatValue = out[BeatTracker.OUT_BEAT]
                // Knocks that have passed (with a little time for the detection to report them).
                while (SYNC_FIRST_BEAT_MS + nextBeat * SYNC_BEAT_MS <= minOf(pos - 200, SYNC_LAST_BEAT_MS)) {
                    nextBeat++
                    expected++
                }
                val rate = sound.sampleRate
                if (seen > sound.onsetTotal) seen = sound.onsetTotal
                while (rate > 0 && seen < sound.onsetTotal) {
                    val ms = sound.onset(seen++) * 1000 / rate
                    val beat = ((ms - SYNC_FIRST_BEAT_MS).toDouble() / SYNC_BEAT_MS).roundToLong()
                    val error = ms - (SYNC_FIRST_BEAT_MS + beat * SYNC_BEAT_MS)
                    if (beat < 0 || abs(error) > 150) continue
                    found++
                    errors.add(error)
                    if (errors.size > 24) errors.removeAt(0)
                    if (errors.size >= 6) {
                        val mean = errors.average()
                        lag = mean.toInt()
                        spread = errors.map { abs(it - mean) }.average().toInt().coerceAtLeast(1)
                    }
                }
            }
        }
    }

    fun save() {
        val measured = errors.size >= 8
        app.settings.update { it.copy(saverSyncMs = offset, saverDetectMs = if (measured) lag else it.saverDetectMs) }
        onClose()
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .then(if (preview) Modifier else Modifier.background(Color.Black))
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { offset = (offset - 10).coerceAtLeast(-SYNC_RANGE); true }
                    Key.DirectionRight -> { offset = (offset + 10).coerceAtMost(SYNC_RANGE); true }
                    Key.DirectionDown -> { screen = (screen + 1) % (PREVIEW_VISUALS.size + 1); true }
                    Key.DirectionUp -> { screen = (screen + PREVIEW_VISUALS.size) % (PREVIEW_VISUALS.size + 1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { save(); true }
                    Key.Back, Key.Escape -> { onClose(); true }
                    else -> true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (previewVisual != null) {
            // The real screensaver with your settings, moved by the knocks: what you set here is what you get.
            val prefs = app.settings.prefs.value
            Screensaver(
                prefs.dimStyle,
                previewVisual,
                prefs.saverBrightness,
                if (prefs.saverReact == SaverReact.Off) SaverReact.Medium else prefs.saverReact,
                ps,
                revealed = true,
            ) { ahead, out ->
                if (player.isPlaying) sound.pulse(player.currentPosition + ahead + lag - offset, out) else out.fill(0f)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            ) {
                Text(
                    stringResource(if (previewVisual == SaverVisual.Aurora) R.string.style_aurora else R.string.style_starfield),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 16.sp,
                )
                Text("‹  ${syncText(offset)}  ›", color = Color.White.copy(alpha = 0.7f), fontSize = 26.sp, style = TextStyle(fontFeatureSettings = "tnum"))
                Text(stringResource(R.string.sync_view_back), color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
            }
            return@Box
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 760.dp)) {
            Text(stringResource(R.string.s_sync), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.sync_help),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            Canvas(Modifier.size(220.dp)) {
                val f = flash.floatValue
                val r = size.minDimension / 2
                drawCircle(Color.White.copy(alpha = 0.18f), r - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.05f + 0.9f * f), r * (0.8f + 0.12f * f))
            }
            Spacer(Modifier.height(36.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text("‹", color = Color.White.copy(alpha = 0.4f), fontSize = 44.sp)
                // Fixed width with equal-width digits, so the arrows never move.
                Text(
                    syncText(offset),
                    color = Color.White,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(240.dp),
                )
                Text("›", color = Color.White.copy(alpha = 0.4f), fontSize = 44.sp)
            }
            Text(
                stringResource(
                    when {
                        offset < 0 -> R.string.sync_earlier
                        offset > 0 -> R.string.sync_later
                        else -> R.string.sync_none
                    },
                ),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                if (found < 6) stringResource(R.string.sync_listening)
                else stringResource(R.string.sync_found, found.coerceAtMost(expected), expected, spread),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.sync_keys), color = Color.White.copy(alpha = 0.4f), fontSize = 15.sp)
            Text(stringResource(R.string.sync_view_hint), color = Color.White.copy(alpha = 0.4f), fontSize = 15.sp)
        }
    }
}
