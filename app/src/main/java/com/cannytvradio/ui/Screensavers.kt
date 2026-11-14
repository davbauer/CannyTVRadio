package com.cannytvradio.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.cannytvradio.data.DimStyle
import com.cannytvradio.data.SaverBrightness
import com.cannytvradio.data.SaverReact
import com.cannytvradio.data.SaverVisual
import com.cannytvradio.player.BeatTracker
import com.cannytvradio.player.PlayerState
import kotlinx.coroutines.delay
import java.util.Date
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/*
 * Screensavers for the dark screen, made for OLED and Mini-LED panels: mostly black, and
 * nothing stays lit in one place. A moving background (aurora, starfield) and the content in
 * front (clock, station) are chosen independently.
 *
 * Cheap on purpose: one clock drives every part, so a frame is drawn once and everything moves
 * in it together. The aurora is drawn at a quarter of the resolution (it is soft anyway), stars
 * are three batched draw calls, and moving blocks are finished layers that only get shifted.
 * [Pacer] picks the frame rate this TV really keeps up with.
 */

/** What every part of the dark screen shares: one clock, the music and the brightness. */
private class SaverEnv(val light: Float, val react: Float) {
    /** Seconds since the dark screen appeared. Reading it in a draw block redraws that block every frame. */
    val time = mutableFloatStateOf(0f)

    /** Seconds since the previous frame. */
    var dt = 0f

    /** Runs like [time] in quiet passages, faster when the music is energetic, and surges on beats. */
    var flow = 0f

    /** How energetic the music is now, smoothed and scaled by "move with the music" (0 when off). */
    var energy = 0f

    /** 1 right on a beat, fading within a quarter second, scaled like [energy]. */
    var beat = 0f

    /** Kick drum and bass line (0..1, quick), scaled like [energy]. */
    var bass = 0f

    /** Hi-hats and cymbals (0..1, quick), scaled like [energy]. */
    var treble = 0f

    /** Breathes with the tempo: 1 on each beat, 0 halfway between (0 while no steady beat is known), scaled like [energy]. */
    var sway = 0f

    /** Runs faster with the treble; makes fine detail shimmer with hi-hats. */
    var shimmer = 0f

    /** Extra look-ahead for visuals on their own surface, which reach the screen a frame later. */
    var extraLeadMs = 0L

    /** Called once per frame after the values above are updated (the aurora hands them to its own surface). */
    var onFrame: (() -> Unit)? = null

    /** A base opacity scaled by the chosen brightness. */
    fun a(base: Float) = (base * light).coerceIn(0f, 0.95f)
}

private val LocalSaver = staticCompositionLocalOf { SaverEnv(1f, 0f) }

/**
 * How far ahead of the position you hear the visuals read the music, in milliseconds: about two
 * refreshes until a drawn frame is on screen, plus half a frame, because a frame stays visible
 * for its whole frame time and should be centered on the beat. The sync helper and the dark
 * screen both use this, so a value calibrated in the helper holds at any frame rate. What is left
 * (the TV's own picture and sound delay) is the per-TV setting from the sync helper.
 */
internal fun visualLeadMs(fps: Int, refresh: Int): Long = 2000L / refresh.coerceAtLeast(1) + 500L / fps.coerceAtLeast(1)

/**
 * [pulse] writes what the music does ([BeatTracker.read]) into an array, for the moment you hear
 * plus the given milliseconds. [revealed] turns true once the app underneath is hidden.
 */
@Composable
fun Screensaver(
    content: DimStyle,
    visual: SaverVisual,
    brightness: SaverBrightness,
    react: SaverReact,
    ps: PlayerState,
    revealed: Boolean,
    pulse: (aheadMs: Long, out: FloatArray) -> Unit,
) {
    val env = remember(brightness, react) { SaverEnv(brightness.level, react.amount) }
    // Always the newest, so a sync change applies while the screensaver runs.
    val currentPulse by rememberUpdatedState(pulse)
    val view = LocalView.current
    remember { Pacer.begin(view.display?.refreshRate ?: 60f) }
    // The aurora draws on its own surface; its frames reach the screen one refresh later.
    env.extraLeadMs = if (visual == SaverVisual.Aurora) 1000L / Pacer.refresh else 0L
    val moving = visual != SaverVisual.None || content != DimStyle.Nothing
    if (moving) {
        MeasureFrames()
        LaunchedEffect(env) {
            val raw = FloatArray(BeatTracker.OUT_SIZE)
            var last = 0L
            var energy = 0f
            env.time.floatValue = Random.nextFloat() * 1000f
            while (true) {
                val rate = Pacer.fps
                // At a lower rate, sleep until shortly before the frame that's due.
                if (last != 0L && rate < Pacer.refresh) delay((1000L / rate - 6).coerceAtLeast(0))
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                    last = now
                    // Look ahead by the time a frame needs to reach the screen, so a beat lands when you hear it.
                    if (env.react > 0f) currentPulse(visualLeadMs(rate, Pacer.refresh) + env.extraLeadMs, raw) else raw.fill(0f)
                    energy += (raw[BeatTracker.OUT_ENERGY] - energy) * (1f - exp(-dt / 0.08f))
                    env.energy = energy * env.react
                    env.beat = raw[BeatTracker.OUT_BEAT] * env.react
                    env.bass = raw[BeatTracker.OUT_BASS] * env.react
                    env.treble = raw[BeatTracker.OUT_TREBLE] * env.react
                    val phase = raw[BeatTracker.OUT_PHASE]
                    env.sway = if (phase < 0f) 0f else (0.5f + 0.5f * kotlin.math.cos(phase * TWO_PI)) * env.react
                    env.shimmer += dt * (0.5f + 2f * env.treble)
                    env.dt = dt
                    env.flow += dt * (1f + 1.3f * env.energy + 0.8f * env.bass + 3f * env.beat)
                    env.time.floatValue += dt
                    env.onFrame?.invoke()
                }
            }
        }
    }
    CompositionLocalProvider(LocalSaver provides env) {
        Box(Modifier.fillMaxSize()) {
            when (visual) {
                SaverVisual.Aurora -> if (revealed) Aurora()
                SaverVisual.Starfield -> Starfield()
                SaverVisual.None -> Unit
            }
            when (content) {
                DimStyle.Time -> DriftingClock(info = false, ps)
                DimStyle.TimeAndInfo -> DriftingClock(info = true, ps)
                DimStyle.AnalogClock -> AnalogClock(ps)
                DimStyle.Station -> StationCard(ps)
                DimStyle.Nothing -> Unit
            }
        }
    }
}

/**
 * Finds the frame rate this TV can really keep up with on the dark screen. Android reports what
 * every drawn frame cost the processor and the graphics chip. When frames often don't fit their
 * time, everything steps down one rate (full, half, a third, a quarter of the display's refresh
 * rate); when they fit easily into the next faster rate, it steps back up. The rate is kept while
 * the app runs, so the next dark screen starts right.
 */
internal object Pacer {
    @Volatile var refresh = 60
        private set
    @Volatile private var tier = 0
    private var start = 0L
    private var frames = 0
    private var over = 0
    private var easy = 0
    private var easyRounds = 0
    /** Calm rounds needed before trying a faster rate; doubles each time a try fails. */
    private var patience = 3
    private var justRaised = false

    /** Frames per second everything on the dark screen draws at right now. */
    val fps: Int get() = (refresh / (tier + 1)).coerceAtLeast(10)

    /** A wanted rate, never above what this TV keeps up with; 0 means as fast as allowed. */
    fun cap(wanted: Int) = if (wanted <= 0) fps else minOf(wanted, fps)

    fun begin(display: Float) {
        refresh = display.toInt().coerceIn(30, 240)
        // Without frame reports (Android 6 and older) stay at a safe half rate.
        if (android.os.Build.VERSION.SDK_INT < 24) tier = maxOf(tier, 1)
        start = android.os.SystemClock.uptimeMillis()
        frames = 0; over = 0; easy = 0; easyRounds = 0; justRaised = false
    }

    /** What one drawn frame cost the processor ([cpu]) and the graphics chip ([gpu]), in nanoseconds. */
    @Synchronized
    fun report(cpu: Long, gpu: Long) {
        // The fade-in still draws the app underneath; those frames say nothing about the screensaver.
        if (android.os.SystemClock.uptimeMillis() - start < 2_500) return
        val cost = maxOf(cpu, gpu)
        val budget = 1_000_000_000L / fps
        if (cost > budget * 9 / 10) over++
        // Only try faster when frames would fit with lots of room, so the rate doesn't flip back and forth.
        if (tier > 0 && cost < 1_000_000_000L / (refresh / tier) * 55 / 100) easy++
        if (++frames < 40) return
        when {
            over * 5 > frames && tier < 3 -> {
                tier++
                if (justRaised) patience = (patience * 2).coerceAtMost(64)
                justRaised = false; easyRounds = 0; log()
            }
            tier > 0 && easy * 10 >= frames * 9 -> if (++easyRounds >= patience) {
                tier--
                justRaised = true; easyRounds = 0; log()
            }
            else -> { easyRounds = 0; justRaised = false }
        }
        frames = 0; over = 0; easy = 0
    }

    private fun log() = android.util.Log.i("CannyTVRadio", "screensaver: $fps fps (display $refresh Hz)")
}

/** Feeds the real cost of every drawn frame to [Pacer] while the dark screen shows. */
@Composable
private fun MeasureFrames() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (android.os.Build.VERSION.SDK_INT < 24 || window == null) return@DisposableEffect onDispose { }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, m, _ ->
            // Real work only: waiting for the frame to start is not the screensaver's cost.
            val cpu = m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) - m.getMetric(android.view.FrameMetrics.UNKNOWN_DELAY_DURATION)
            val gpu = if (android.os.Build.VERSION.SDK_INT >= 31) m.getMetric(android.view.FrameMetrics.GPU_DURATION) else 0L
            Pacer.report(cpu, gpu)
        }
        window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { runCatching { window.removeOnFrameMetricsAvailableListener(listener) } }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * A slow path that never repeats (two sine waves of 13 and 17 minutes) across the free space
 * around a block of [block] width. Read inside graphicsLayer, so moving costs no layout.
 */
@Composable
private fun DriftingBlock(block: Dp, content: @Composable () -> Unit) {
    val env = LocalSaver.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val blockPx = with(LocalDensity.current) { block.toPx() }
        val reachX = (constraints.maxWidth - blockPx) / 2f * 0.9f
        val reachY = constraints.maxHeight * 0.24f
        // Start somewhere different each time, so repeated dark screens don't share a spot.
        val start = remember { Random.nextFloat() * 10_000f }
        Box(
            Modifier.width(block).graphicsLayer {
                // A finished image, moved with filtering, glides smoothly between pixels.
                compositingStrategy = CompositingStrategy.Offscreen
                val t = start + env.time.value
                translationX = reachX * sin(t * TWO_PI / 780f)
                translationY = reachY * sin(t * TWO_PI / 1020f + 1.3f)
            },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** Digital time, optionally with station and song. Fixed width, equal-width digits. */
@Composable
private fun DriftingClock(info: Boolean, ps: PlayerState) {
    val env = LocalSaver.current
    val time = rememberTimeText()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        DriftingBlock(maxWidth * 0.5f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CalmChange(time) { t ->
                    Text(
                        t,
                        color = Color.White.copy(alpha = env.a(0.45f)),
                        fontSize = 112.sp,
                        fontWeight = FontWeight.ExtraLight,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (info) StationLines(ps)
            }
        }
    }
}

@Composable
private fun StationLines(ps: PlayerState, nameSize: Int = 22) {
    val env = LocalSaver.current
    CalmChange(ps.station?.name.orEmpty()) { name ->
        Text(
            name, color = Color.White.copy(alpha = env.a(0.34f)), fontSize = nameSize.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(4.dp))
    CalmChange(ps.song) { song ->
        Text(
            song, color = Color.White.copy(alpha = env.a(0.26f)), fontSize = 18.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun rememberTimeText(): String {
    val context = LocalContext.current
    var time by remember { mutableStateOf(DateFormat.getTimeFormat(context).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            time = DateFormat.getTimeFormat(context).format(Date())
        }
    }
    return time
}

/** A minimal clock face with a smoothly sweeping second hand, and the station below. */
@Composable
private fun AnalogClock(ps: PlayerState) {
    val env = LocalSaver.current
    val accent = LocalAccent.current
    DriftingBlock(320.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(280.dp)) {
                env.time.value // redraw every frame for the sweeping second hand
                val now = System.currentTimeMillis()
                val day = ((now + TimeZone.getDefault().getOffset(now)) % 86_400_000L) / 1000.0
                val r = size.minDimension / 2 - 4.dp.toPx()
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(Color.White.copy(alpha = env.a(0.16f)), r, c, style = Stroke(1.5.dp.toPx()))
                for (i in 0 until 12) {
                    val long = i % 3 == 0
                    rotate(i * 30f, c) {
                        drawLine(
                            Color.White.copy(alpha = env.a(if (long) 0.45f else 0.25f)),
                            Offset(c.x, c.y - r + 6.dp.toPx()),
                            Offset(c.x, c.y - r + (if (long) 22 else 14).dp.toPx()),
                            strokeWidth = (if (long) 3 else 2).dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
                fun hand(angle: Double, length: Float, width: Dp, color: Color) = rotate(angle.toFloat(), c) {
                    drawLine(color, c, Offset(c.x, c.y - r * length), strokeWidth = width.toPx(), cap = StrokeCap.Round)
                }
                hand((day / 3600 % 12) / 12 * 360, 0.5f, 6.dp, Color.White.copy(alpha = env.a(0.5f)))
                hand((day / 60 % 60) / 60 * 360, 0.75f, 4.dp, Color.White.copy(alpha = env.a(0.5f)))
                hand((day % 60) / 60 * 360, 0.85f, 1.5.dp, accent.copy(alpha = env.a(0.7f)))
                drawCircle(Color.White.copy(alpha = env.a(0.55f)), 5.dp.toPx(), c)
            }
            Spacer(Modifier.height(18.dp))
            StationLines(ps, nameSize = 20)
        }
    }
}

/** The station's logo, dimmed, with name and song. */
@Composable
private fun StationCard(ps: PlayerState) {
    val env = LocalSaver.current
    DriftingBlock(420.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(ps.station, Modifier.size(width = 240.dp, height = 170.dp).graphicsLayer { alpha = env.a(0.55f) }, corner = 28.dp)
            Spacer(Modifier.height(22.dp))
            StationLines(ps, nameSize = 26)
        }
    }
}

/** Height of the aurora's own surface; the TV's display hardware scales it up to the full screen for free. */
private const val AURORA_HEIGHT = 720

/**
 * Northern lights, computed per pixel by a shader on the graphics chip: curtains of fine
 * vertical rays with a sharp green lower edge fading up into violet, folding slowly while the
 * rays shimmer. Music makes the curtains move faster and reach higher; a beat makes them flare
 * and sends a wave of light along them.
 *
 * It draws into its own small surface behind the app (1280 x 720 on a 16:9 screen), which the
 * display hardware scales up. A 4K TV then computes under a million pixels per frame instead of
 * eight million, and the app's own window stays untouched.
 */
@Composable
private fun Aurora() {
    val env = LocalSaver.current
    val renderer = remember { AuroraRenderer() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val aspect = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)
        val surfaceWidth = (AURORA_HEIGHT * aspect).toInt().coerceIn(320, 2048)
        var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
        AndroidView(
            factory = { context ->
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(2)
                    holder.setFixedSize(surfaceWidth, AURORA_HEIGHT)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    surface = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        DisposableEffect(env, surface) {
            val view = surface
            env.onFrame = if (view == null) null else {
                {
                    renderer.time = env.time.value
                    renderer.flow = env.flow
                    renderer.energy = env.energy
                    renderer.beat = env.beat
                    renderer.bass = env.bass
                    renderer.treble = env.treble
                    renderer.sway = env.sway
                    renderer.shimmer = env.shimmer
                    renderer.light = env.light
                    view.requestRender()
                }
            }
            onDispose { env.onFrame = null }
        }
    }
}

private class AuroraRenderer : GLSurfaceView.Renderer {
    @Volatile var time = 0f
    @Volatile var flow = 0f
    @Volatile var energy = 0f
    @Volatile var beat = 0f
    @Volatile var bass = 0f
    @Volatile var treble = 0f
    @Volatile var sway = 0f
    @Volatile var shimmer = 0f
    @Volatile var light = 1f

    private var program = 0
    private var aPos = 0
    private var uRes = 0
    private var uFlow = 0
    private var uEnergy = 0
    private var uBeat = 0
    private var uBass = 0
    private var uTreble = 0
    private var uSway = 0
    private var uShimmer = 0
    private var uLight = 0
    private var uFade = 0
    private var uNoise = 0
    private var noiseTexture = 0
    private var costSum = 0L
    private var costCount = 0
    private var width = 1
    private var height = 1
    private var start = -1f
    private val quad = java.nio.ByteBuffer.allocateDirect(8 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        program = link(AURORA_VERTEX, AURORA_FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uRes = GLES20.glGetUniformLocation(program, "uRes")
        uFlow = GLES20.glGetUniformLocation(program, "uFlow")
        uEnergy = GLES20.glGetUniformLocation(program, "uEnergy")
        uBeat = GLES20.glGetUniformLocation(program, "uBeat")
        uBass = GLES20.glGetUniformLocation(program, "uBass")
        uTreble = GLES20.glGetUniformLocation(program, "uTreble")
        uSway = GLES20.glGetUniformLocation(program, "uSway")
        uShimmer = GLES20.glGetUniformLocation(program, "uShimmer")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uFade = GLES20.glGetUniformLocation(program, "uFade")
        uNoise = GLES20.glGetUniformLocation(program, "uNoise")
        noiseTexture = makeNoiseTexture()
    }

    /**
     * Smooth noise, computed once: a 64 x 64 grid of random values, smoothly blended into a
     * 256 x 256 texture that repeats seamlessly. Reading it is far cheaper for the graphics chip
     * than computing noise per pixel.
     */
    private fun makeNoiseTexture(): Int {
        val grid = 64
        val size = 256
        val random = java.util.Random(7)
        val cells = FloatArray(grid * grid) { random.nextFloat() }
        val bytes = java.nio.ByteBuffer.allocateDirect(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val gx = x * grid / size.toFloat()
                val gy = y * grid / size.toFloat()
                val x0 = gx.toInt()
                val y0 = gy.toInt()
                var fx = gx - x0
                var fy = gy - y0
                fx = fx * fx * (3 - 2 * fx)
                fy = fy * fy * (3 - 2 * fy)
                fun c(i: Int, j: Int) = cells[(j % grid) * grid + (i % grid)]
                val top = c(x0, y0) + (c(x0 + 1, y0) - c(x0, y0)) * fx
                val bottom = c(x0, y0 + 1) + (c(x0 + 1, y0 + 1) - c(x0, y0 + 1)) * fx
                bytes.put(((top + (bottom - top) * fy) * 255).toInt().coerceIn(0, 255).toByte())
            }
        }
        bytes.position(0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, size, size, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, bytes)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        return ids[0]
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        val began = System.nanoTime()
        val now = time
        if (start < 0f) start = now
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTexture)
        GLES20.glUniform1i(uNoise, 0)
        GLES20.glUniform2f(uRes, width.toFloat(), height.toFloat())
        // Kept small so the shader's math stays precise even after hours.
        GLES20.glUniform1f(uFlow, flow % 2000f)
        GLES20.glUniform1f(uEnergy, energy)
        GLES20.glUniform1f(uBeat, beat)
        GLES20.glUniform1f(uBass, bass)
        GLES20.glUniform1f(uTreble, treble)
        GLES20.glUniform1f(uSway, sway)
        GLES20.glUniform1f(uShimmer, shimmer % 2000f)
        GLES20.glUniform1f(uLight, light)
        // Fades in over two seconds once the app underneath is gone.
        GLES20.glUniform1f(uFade, ((now - start) / 2f).coerceIn(0f, 1f))
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        // Wait for the chip, so the pacer learns what a frame really costs here.
        GLES20.glFinish()
        val cost = System.nanoTime() - began
        Pacer.report(cost, 0L)
        costSum += cost
        if (++costCount == 120) {
            android.util.Log.i("CannyTVRadio", "aurora: ${costSum / costCount / 100_000 / 10f} ms per frame at ${width}x$height")
            costSum = 0L
            costCount = 0
        }
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.w("CannyTVRadio", "aurora: shader failed: ${GLES20.glGetShaderInfoLog(shader)}")
            return shader
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        return p
    }
}

private const val AURORA_VERTEX = """
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

private const val AURORA_FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 uRes;
uniform float uFlow;
uniform float uEnergy;
uniform float uBeat;
uniform float uBass;
uniform float uTreble;
uniform float uSway;
uniform float uShimmer;
uniform float uLight;
uniform float uFade;
uniform sampler2D uNoise;

// Smooth noise from the pre-computed texture: one cell per unit.
float n(vec2 p) { return texture2D(uNoise, p * 0.015625).r; }

// One curtain: its lower edge hangs at "base" (0 = bottom of the screen) and folds; light
// rises from the edge and fades upwards, split into fine shimmering rays.
vec3 curtain(vec2 uv, float base, float tall, float speed, float seed, float bright, vec3 low, vec3 high) {
    float t = uFlow * speed;
    float x = uv.x;
    float edge = base
        + 0.055 * sin(x * 4.7 + t * 0.20 + seed)
        + 0.030 * sin(x * 10.3 - t * 0.33 + seed * 2.0)
        + 0.060 * (n(vec2(x * 3.0 + t * 0.06, seed * 5.0)) - 0.5);
    float h = uv.y - edge;
    float height = tall * (0.7 + 0.3 * sin(x * 3.3 - t * 0.15 + seed)) * (1.0 + 0.2 * uEnergy + 0.3 * uBass + 0.35 * uBeat);
    // Far from the curtain there is nothing to compute.
    if (h < -0.03 || h > height * 2.6) return vec3(0.0);
    float lowerEdge = smoothstep(-0.025, 0.004, h);
    float fall = exp(-max(h, 0.0) / height * 2.4);
    float rays = n(vec2(x * 55.0 + seed * 7.0 + t * 0.22, h * 1.2 - t * 0.08 + seed * 11.0));
    // Fine rays shimmer faster and stronger with hi-hats and cymbals.
    float fine = n(vec2(x * 120.0 - uShimmer * 0.9, h * 2.0 + seed * 13.0));
    float r = rays * rays * (0.6 + (0.4 + 0.4 * uTreble) * fine) * 1.6;
    // Bright stretches with dark gaps in between, drifting along the curtain.
    float patch = smoothstep(0.32, 0.78, n(vec2(x * 2.4 + t * 0.045 + seed * 3.0, seed * 9.0)));
    float wave = 1.0 + 0.9 * uBeat * (0.5 + 0.5 * sin(x * 9.0 - uFlow * 4.0));
    float k = lowerEdge * fall * r * patch * wave * bright * (1.0 + 0.3 * uEnergy + 0.25 * uBass + 1.0 * uBeat + 0.2 * uSway);
    vec3 col = mix(low, high, smoothstep(0.0, height * 1.2, h));
    // A thin pink fringe at the lower edge on strong beats, like a bright real aurora.
    col = mix(col, vec3(0.95, 0.35, 0.65), 0.3 * uBeat * (1.0 - smoothstep(0.0, 0.02, abs(h))));
    return col * k;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    uv.x *= uRes.x / uRes.y / 1.7778;
    vec3 green = vec3(0.16, 0.95, 0.50);
    vec3 teal = vec3(0.12, 0.70, 0.72);
    vec3 violet = vec3(0.42, 0.22, 0.85);
    vec3 magenta = vec3(0.55, 0.22, 0.80);
    vec3 c = curtain(uv, 0.56, 0.22, 0.70, 3.1, 0.30, teal, magenta)
           + curtain(uv, 0.30, 0.30, 1.00, 0.0, 0.75, green, violet)
           + curtain(uv, 0.12, 0.18, 1.35, 7.7, 0.30, green, violet);
    // Soft tone curve: overlapping curtains glow brighter but never clip into white.
    c = 1.0 - exp(-c * 1.2);
    c *= uLight * 0.7 * uFade;
    // Tiny noise against color banding in the dark gradients.
    c += (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;
    gl_FragColor = vec4(max(c, 0.0), 1.0);
}
"""

private const val STARS = 170
private const val STAR_GROUPS = 3

/**
 * Flying through stars. Music speeds the flight up, and on a beat the stars surge forward and
 * stretch into short streaks, so the change of speed is easy to see. All stars are drawn in
 * three batched calls (far, middle, near) from reused arrays: a frame allocates nothing.
 */
@Composable
private fun Starfield() {
    val env = LocalSaver.current
    // x, y, depth per star; filled on the first frame, when the screen size is known.
    val stars = remember { FloatArray(STARS * 3) }
    val ready = remember { booleanArrayOf(false) }
    val lines = remember { Array(STAR_GROUPS) { FloatArray(STARS * 4) } }
    val counts = remember { IntArray(STAR_GROUPS) }
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = android.graphics.Color.WHITE
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        env.time.value // redraw every frame
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val scale = h * 0.5f
        // At the far end, stars spread exactly over the screen.
        val rx = cx / scale
        val ry = cy / scale
        if (!ready[0]) {
            for (i in 0 until STARS) {
                stars[i * 3] = (Random.nextFloat() * 2 - 1) * rx
                stars[i * 3 + 1] = (Random.nextFloat() * 2 - 1) * ry
                stars[i * 3 + 2] = 0.05f + Random.nextFloat() * 0.95f
            }
            ready[0] = true
        }
        // Flight speed: energy and bass push, beats surge, and it breathes a little with the tempo.
        val speed = 0.07f * (1f + 2f * env.energy + 1.2f * env.bass + 6f * env.beat + 0.5f * env.sway)
        val dz = env.dt * speed
        // The streak shows where the star was a moment ago: a dot when calm, a line when fast.
        val tail = speed * 0.09f
        counts.fill(0)
        for (i in 0 until STARS) {
            val k = i * 3
            var z = stars[k + 2] - dz
            var px = cx + stars[k] / z * scale
            var py = cy + stars[k + 1] / z * scale
            if (z <= 0.03f || px < 0 || py < 0 || px > w || py > h) {
                stars[k] = (Random.nextFloat() * 2 - 1) * rx
                stars[k + 1] = (Random.nextFloat() * 2 - 1) * ry
                z = 1f
                px = cx + stars[k] * scale
                py = cy + stars[k + 1] * scale
            }
            stars[k + 2] = z
            val tz = (z + tail).coerceAtMost(1.2f)
            val g = ((1f - z) * STAR_GROUPS).toInt().coerceIn(0, STAR_GROUPS - 1)
            val n = counts[g] * 4
            val line = lines[g]
            line[n] = cx + stars[k] / tz * scale
            line[n + 1] = cy + stars[k + 1] / tz * scale
            line[n + 2] = px
            line[n + 3] = py
            counts[g]++
        }
        val unit = 1.dp.toPx()
        val flash = 1f + 0.5f * env.beat + 0.15f * env.bass
        drawIntoCanvas { canvas ->
            for (g in 0 until STAR_GROUPS) {
                if (counts[g] == 0) continue
                val near = (g + 0.5f) / STAR_GROUPS
                paint.strokeWidth = unit * (1.1f + 3f * near)
                // Far stars twinkle with hi-hats and cymbals, near ones flash with the beat.
                val twinkle = 1f + 0.6f * env.treble * (1f - near)
                paint.alpha = (255 * env.a((0.14f + 0.5f * near) * flash * twinkle)).toInt()
                canvas.nativeCanvas.drawLines(lines[g], 0, counts[g] * 4, paint)
            }
        }
    }
}

/** New text fades in with a slight rise while the old one fades out; slow enough to feel calm. */
@Composable
internal fun CalmChange(value: String, content: @Composable (String) -> Unit) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (fadeIn(tween(900, delayMillis = 200)) + slideInVertically(tween(1100)) { it / 6 }) togetherWith
                fadeOut(tween(700))
        },
        contentAlignment = Alignment.Center,
        label = "calm",
        modifier = Modifier.fillMaxWidth(),
    ) { content(it) }
}

internal const val TWO_PI = (2 * PI).toFloat()
