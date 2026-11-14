package com.cannytvradio.ui

import android.app.Activity
import android.content.res.Resources
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cannytvradio.R
import com.cannytvradio.RadioApp
import com.cannytvradio.data.DimStyle
import com.cannytvradio.data.Station
import com.cannytvradio.player.PlayerState
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.mutableFloatStateOf
import java.util.Date
import kotlin.random.Random

enum class Tab(val label: Int) {
    Home(R.string.tab_home),
    Search(R.string.tab_search),
    Regions(R.string.tab_regions),
    Favorites(R.string.tab_favorites),
    Settings(R.string.tab_settings),
    NowPlaying(R.string.now_playing),
}

/** Screen-level state shared by the activity (key handling) and the composables. */
class Ui {
    var tab by mutableStateOf(Tab.Home)
    /** Favorites when there are any, otherwise Home. Back from other pages returns here. */
    var startTab = Tab.Home
    /** Where Back goes from the full player view. */
    var back = Tab.Home
    var toast by mutableStateOf<String?>(null)
    var darkNow by mutableStateOf(false)
    var dimmed by mutableStateOf(false)
    var lastInput by mutableLongStateOf(SystemClock.uptimeMillis())
    /** Focus is somewhere in the page content (not the player or the tabs). */
    var contentFocused by mutableStateOf(false)
    /** A text field has focus, so number keys type instead of switching stations. */
    var typing = false
    var initialFocusDone = false
    val focus = FocusMemory()
    val playFocus = FocusRequester()
    /** One focus target per tab in the top bar. */
    val tabFocus = Tab.entries.associateWith { FocusRequester() }

    fun openPlayer() {
        if (tab != Tab.NowPlaying) back = tab
        tab = Tab.NowPlaying
    }
}

val LocalUi = staticCompositionLocalOf<Ui> { error("Ui not provided") }

/** What a tile can do; shared by every screen. */
class Actions(private val app: RadioApp, private val ui: Ui, private val res: Resources) {
    /** Plays right away; the player on the left shows it, the page stays where it is. */
    fun play(station: Station) = app.player.play(station)

    fun toggleFavorite(station: Station) {
        val was = app.repo.isFavorite(station.id)
        app.repo.toggleFavorite(station)
        toast(res.getString(if (was) R.string.removed_favorite else R.string.added_favorite, station.name))
    }

    fun toggleAutoDark() {
        app.settings.update { it.copy(dimEnabled = !it.dimEnabled) }
        val p = app.settings.prefs.value
        toast(if (p.dimEnabled) res.getString(R.string.auto_dark_on, p.dimAfter.seconds.toDuration(res)) else res.getString(R.string.auto_dark_off))
    }

    /** Off, 15, 30, 60, 90 minutes; says what was set. */
    fun cycleSleepTimer() {
        app.player.cycleSleepTimer()
        val at = app.player.state.value.sleepAt
        val minutes = ((at - System.currentTimeMillis() + 30_000) / 60_000).toInt()
        toast(if (at == 0L) res.getString(R.string.sleep_cleared) else res.getString(R.string.sleep_set, (minutes * 60).toDuration(res)))
    }

    fun toast(text: String) {
        ui.toast = text
    }
}

fun Int.toDuration(res: Resources): String =
    if (this < 60) res.getString(R.string.seconds_short, this) else res.getString(R.string.minutes_short, this / 60)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun RadioRoot(app: RadioApp, ui: Ui) {
    val ps by app.player.state.collectAsState()
    val prefs by app.settings.prefs.collectAsState()
    val context = LocalContext.current
    val actions = remember { Actions(app, ui, context.resources) }
    val holder = rememberSaveableStateHolder()
    val accent = rememberAccent(ps.station?.favicon)
    val mood = rememberMood()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val calm = remember(density) { CalmScroll(with(density) { EDGE.toPx() }) }

    // Dark screen after the chosen idle time while the radio plays, on every page.
    val active = ps.station != null && (ps.playing || ps.buffering)
    LaunchedEffect(prefs.dimEnabled, prefs.dimAfter, active) {
        while (true) {
            val idle = SystemClock.uptimeMillis() - ui.lastInput
            ui.dimmed = ui.darkNow || (prefs.dimEnabled && active && idle > prefs.dimAfter.seconds * 1000L)
            delay(500)
        }
    }
    // While playing, our dark screen replaces the TV's screensaver; the backlight goes as low as the TV allows.
    val window = (context as? Activity)?.window
    LaunchedEffect(active, prefs.dimEnabled) {
        if (active && prefs.dimEnabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(ui.dimmed) {
        window?.attributes = window?.attributes?.apply {
            screenBrightness = if (ui.dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    CompositionLocalProvider(
        LocalAccent provides accent,
        LocalUi provides ui,
        LocalFocusMemory provides ui.focus,
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides calm,
    ) {
        // Once the dark screen has faded in, the app underneath is not drawn at all.
        var hidden by remember { mutableStateOf(false) }
        LaunchedEffect(ui.dimmed) {
            if (ui.dimmed) {
                delay(1600)
                hidden = true
            } else {
                hidden = false
            }
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (hidden) 0f else 1f }) {
            Aurora(accent, mood, ps.playing, ui)
            Row(Modifier.fillMaxSize()) {
                PlayerPanel(app, ps, ui, actions)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(app, ui)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onFocusChanged { ui.contentFocused = it.hasFocus }
                            // Down never leaves the page (it used to jump into the player when nothing was below),
                            // and coming back from the player or the tabs lands on the tile you were on.
                            .focusProperties {
                                exit = {
                                    when (it) {
                                        FocusDirection.Down -> FocusRequester.Cancel
                                        // Up from the top of a page always lands on the current tab.
                                        FocusDirection.Up -> ui.tabFocus.getValue(if (ui.tab == Tab.NowPlaying) ui.back else ui.tab)
                                        else -> FocusRequester.Default
                                    }
                                }
                            }
                            .focusRestorer()
                            .focusGroup(),
                    ) {
                        AnimatedContent(
                            targetState = ui.tab,
                            transitionSpec = { fadeIn(tween(180, delayMillis = 40)) togetherWith fadeOut(tween(90)) },
                            label = "tab",
                        ) { tab ->
                            holder.SaveableStateProvider(tab.name) {
                                when (tab) {
                                    Tab.Home -> HomeScreen(app, ps, actions)
                                    Tab.Search -> SearchScreen(app, ps, actions)
                                    Tab.Regions -> RegionsScreen(app, ps, actions)
                                    Tab.Favorites -> FavoritesScreen(app, ps, actions)
                                    Tab.Settings -> SettingsScreen(app, actions)
                                    Tab.NowPlaying -> NowPlayingScreen(app, ps, actions)
                                }
                            }
                        }
                        Toast(ui, Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp))
                    }
                }
            }
            }
            Ambient(ui.dimmed, prefs.dimStyle, ps)
        }
    }
    // Back from anywhere in the page jumps to Play. From the player or the tabs it goes back a page, then out.
    BackHandler(enabled = ui.contentFocused || ui.tab != ui.startTab) {
        when {
            ui.contentFocused && ps.station != null -> runCatching { ui.playFocus.requestFocus() }
            ui.tab == Tab.NowPlaying -> ui.tab = ui.back
            else -> ui.tab = ui.startTab
        }
    }
}

/**
 * Calm background: dark graphite with two soft glows, one in the station's washed-out color,
 * one in the faint tint of the time of day.
 *
 * While music plays the glows drift and breathe slowly. To stay light on the TV's GPU the motion
 * is stepped about 15 times a second (slow enough to look smooth), pauses for 2 seconds after
 * every key press so navigation gets the full frame budget, and stops completely when nothing plays.
 * Each glow is a tiny pre-rendered image, so a frame costs two texture draws.
 */
@Composable
private fun Aurora(accent: Color, mood: Mood, playing: Boolean, ui: Ui) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        while (playing) {
            delay(66)
            if (!ui.dimmed && SystemClock.uptimeMillis() - ui.lastInput > 2_000) phase += 0.066f
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            // Own cached layer: while scrolling it is reused as one finished image instead of repainting the glows.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
            val station = glowBitmap(accent, 0.30f)
            val time = glowBitmap(mood.tint, mood.strength)
            val w = size.width
            val h = size.height
            onDrawBehind {
                drawRect(Glass.bg)
                val t = phase
                val breathe = 1f + 0.06f * sin(t * 0.45f)
                glow(station, w * (0.30f + 0.10f * sin(t * 0.13f)), h * (0.18f + 0.12f * cos(t * 0.11f)), w * 0.62f * breathe)
                glow(time, w * (0.82f + 0.08f * cos(t * 0.09f)), h * (0.92f + 0.10f * sin(t * 0.07f)), w * 0.70f * (2f - breathe))
            }
        },
    )
}

private fun DrawScope.glow(bitmap: ImageBitmap, cx: Float, cy: Float, radius: Float) {
    val d = (radius * 2).toInt()
    drawImage(
        bitmap,
        dstOffset = IntOffset((cx - radius).toInt(), (cy - radius).toInt()),
        dstSize = IntSize(d, d),
        filterQuality = FilterQuality.Low,
    )
}

/** A 64 px round glow, transparent at the edge; scaled up it stays perfectly soft. */
private fun glowBitmap(color: Color, alpha: Float): ImageBitmap {
    val n = 64
    val bitmap = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.shader = android.graphics.RadialGradient(
        n / 2f, n / 2f, n / 2f,
        intArrayOf(color.copy(alpha = alpha).toArgb(), color.copy(alpha = alpha * 0.45f).toArgb(), android.graphics.Color.TRANSPARENT),
        floatArrayOf(0f, 0.45f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    android.graphics.Canvas(bitmap).drawRect(0f, 0f, n.toFloat(), n.toFloat(), paint)
    return bitmap.asImageBitmap()
}

private val TOP_TABS = listOf(Tab.Favorites, Tab.Home, Tab.Search, Tab.Regions)

/** Floating glass capsule with the pages; clock and settings on the right. Moving through the tabs switches the page. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(app: RadioApp, ui: Ui) {
    val offline by app.repo.offline.collectAsState()
    val requesters = ui.tabFocus
    Row(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .focusProperties {
                enter = { requesters.getValue(if (ui.tab in TOP_TABS || ui.tab == Tab.Settings) ui.tab else ui.startTab) }
            }
            .focusGroup()
            .padding(start = EDGE - 6.dp, end = EDGE, top = 18.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.glass(RoundedCornerShape(50), tint = 0.45f).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TOP_TABS.forEach { tab ->
                Pill(
                    stringResource(tab.label),
                    selected = ui.tab == tab,
                    onClick = { ui.tab = tab },
                    onFocus = { ui.tab = tab },
                    focusRequester = requesters.getValue(tab),
                    icon = if (tab == Tab.Search) Ico.Search else null,
                    height = 40.dp,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (offline) {
            Text(stringResource(R.string.offline_short), color = Color(0xFFFFC56B), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(14.dp))
        }
        Clock()
        Spacer(Modifier.width(10.dp))
        val update by app.updater.state.collectAsState()
        Box {
            Box(Modifier.glass(RoundedCornerShape(50), tint = 0.45f).padding(5.dp)) {
                Pill(
                    "",
                    selected = ui.tab == Tab.Settings,
                    onClick = { ui.tab = Tab.Settings },
                    onFocus = { ui.tab = Tab.Settings },
                    focusRequester = requesters.getValue(Tab.Settings),
                    icon = Ico.Sliders,
                    height = 40.dp,
                )
            }
            // A new version is waiting in the settings.
            if (update.available != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(11.dp).clip(CircleShape).background(Color(0xFF6FE3A0)))
            }
        }
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(10_000)
        }
    }
    Text(DateFormat.getTimeFormat(context).format(now), color = Glass.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
}

/**
 * The player, always on the left: one press of Back (or Left from the first tile) reaches it.
 * Artwork opens the full view; below are the controls and the quick settings.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerPanel(app: RadioApp, ps: PlayerState, ui: Ui, actions: Actions) {
    val station = ps.station
    val prefs by app.settings.prefs.collectAsState()
    val favorites by app.repo.favorites.collectAsState()
    val isFavorite = station != null && favorites.any { it.id == station.id }
    val player = app.player
    val sleepMinutes = rememberSleepMinutes(ps.sleepAt)
    val accent = LocalAccent.current

    Column(
        Modifier
            .width(250.dp)
            .fillMaxHeight()
            // Cached layer: only repainted when something in the player changes, not while the page scrolls.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .padding(start = 18.dp, top = 18.dp, bottom = 18.dp)
            .glass(RoundedCornerShape(30.dp), tint = 0.5f)
            .focusProperties { enter = { ui.playFocus } }
            .focusGroup()
            .padding(horizontal = 18.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FocusCard(
            onClick = { if (station != null) ui.openPlayer() },
            shape = RoundedCornerShape(22.dp),
            focusedScale = 1.04f,
            background = Color.Transparent,
            focusedBackground = Color.Transparent,
            modifier = Modifier.fillMaxWidth().aspectRatio(ART_RATIO),
        ) {
            Logo(station, Modifier.fillMaxSize(), corner = 22.dp)
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                station?.name ?: stringResource(R.string.nothing_playing),
                color = Glass.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            AnimatedContent(
                targetState = subtitle(ps),
                transitionSpec = {
                    (fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 2 }) togetherWith
                        (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 })
                },
                label = "subtitle",
                modifier = Modifier.height(38.dp),
            ) { text ->
                Text(text, color = Glass.textDim, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.weight(1f))
        LiveLine(ps)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Ico.Previous, stringResource(R.string.previous), { player.skip(-1) }, size = 52.dp, enabled = favorites.isNotEmpty())
            PlayButton(
                playing = ps.playing,
                buffering = ps.buffering && station != null,
                onClick = { if (station == null) app.repo.recent.value.firstOrNull()?.let(player::play) else player.toggle() },
                contentDescription = stringResource(if (ps.playing) R.string.pause else R.string.play),
                focusRequester = ui.playFocus,
            )
            IconButton(Ico.Next, stringResource(R.string.next), { player.skip(1) }, size = 52.dp, enabled = favorites.isNotEmpty())
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
                if (isFavorite) Ico.HeartFilled else Ico.Heart,
                stringResource(if (isFavorite) R.string.unfavorite else R.string.favorite),
                { station?.let(actions::toggleFavorite) },
                size = 48.dp,
                tint = Glass.text,
                enabled = station != null,
            )
            IconButton(
                Ico.Timer,
                stringResource(R.string.sleep_off),
                actions::cycleSleepTimer,
                size = 48.dp,
                active = sleepMinutes > 0,
                enabled = station != null,
                badge = sleepMinutes.takeIf { it > 0 }?.toString(),
            )
            IconButton(
                Ico.Moon,
                stringResource(R.string.auto_dark),
                actions::toggleAutoDark,
                size = 48.dp,
                active = prefs.dimEnabled,
                onLongClick = { ui.darkNow = true },
            )
            IconButton(Ico.Expand, stringResource(R.string.now_playing), { ui.openPlayer() }, size = 48.dp, enabled = station != null)
        }
    }
}

@Composable
private fun subtitle(ps: PlayerState): String = when {
    ps.station == null -> stringResource(R.string.pick_station)
    ps.failed -> stringResource(R.string.failed_short)
    ps.buffering -> stringResource(R.string.connecting)
    ps.song.isNotBlank() -> ps.song
    ps.playing -> stringResource(R.string.live)
    else -> stringResource(R.string.paused)
}

/** Thin line above the controls: a light runs along it while connecting, it glows while live. */
@Composable
private fun LiveLine(ps: PlayerState) {
    val accent = LocalAccent.current
    val connecting = ps.buffering && ps.station != null
    // The running light only exists while connecting, so a playing radio draws no frames here.
    val x = if (connecting) {
        rememberInfiniteTransition(label = "line").animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    } else null
    Canvas(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp))) {
        drawRect(Color.White.copy(alpha = 0.08f))
        when {
            x != null -> drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, accent, Color.Transparent),
                    startX = size.width * x.value - size.width * 0.3f,
                    endX = size.width * x.value + size.width * 0.3f,
                ),
            )
            ps.playing -> drawRect(Brush.horizontalGradient(listOf(accent.copy(alpha = 0f), accent, accent.copy(alpha = 0f))))
        }
    }
}

@Composable
fun rememberSleepMinutes(sleepAt: Long): Int {
    val minutes by produceState(0, sleepAt) {
        while (sleepAt > 0) {
            value = (((sleepAt - System.currentTimeMillis()) / 60_000) + 1).toInt().coerceAtLeast(1)
            delay(15_000)
        }
        value = 0
    }
    return if (sleepAt > 0) minutes else 0
}

@Composable
private fun Toast(ui: Ui, modifier: Modifier) {
    val text = ui.toast
    LaunchedEffect(text) {
        if (text != null) {
            delay(2600)
            ui.toast = null
        }
    }
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        Text(
            text ?: "",
            color = Glass.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .glass(RoundedCornerShape(50), tint = 0.85f)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

/**
 * The dark screen, made for OLED and Mini-LED panels: black everywhere, the backlight at its lowest.
 *
 * The text block has a fixed width (long titles end in "…", digits all have the same width),
 * so nothing in it shifts when the time, station or song changes. It is rendered once into its
 * own image and only that image glides, on a slow path that never repeats (two sine waves with
 * periods of 13 and 17 minutes), with sub-pixel smoothing: no snapping to the pixel grid, no
 * flicker, and no pixel stays lit in one place. Any key brings the app back.
 */
@Composable
private fun Ambient(visible: Boolean, style: DimStyle, ps: PlayerState) {
    AnimatedVisibility(visible, enter = fadeIn(tween(1500)), exit = fadeOut(tween(200))) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (style != DimStyle.Nothing) {
                val context = LocalContext.current
                val block = maxWidth * 0.5f
                val reachX = (constraints.maxWidth - with(androidx.compose.ui.platform.LocalDensity.current) { block.toPx() }) / 2f * 0.9f
                val reachY = constraints.maxHeight * 0.26f
                // Start somewhere different each time, so repeated dark screens don't share a spot.
                val seconds = remember { mutableFloatStateOf(Random.nextFloat() * 10_000f) }
                var time by remember { mutableStateOf(DateFormat.getTimeFormat(context).format(Date())) }
                LaunchedEffect(Unit) {
                    var last = 0L
                    var sinceClock = 0f
                    while (true) {
                        androidx.compose.runtime.withFrameNanos { now ->
                            if (last != 0L) {
                                val dt = (now - last) / 1e9f
                                seconds.floatValue += dt
                                sinceClock += dt
                            }
                            last = now
                        }
                        if (sinceClock > 5f) {
                            sinceClock = 0f
                            time = DateFormat.getTimeFormat(context).format(Date())
                        }
                    }
                }
                Column(
                    Modifier
                        .width(block)
                        .graphicsLayer {
                            // A finished image moved with filtering glides smoothly between pixels.
                            compositingStrategy = CompositingStrategy.Offscreen
                            val t = seconds.floatValue
                            translationX = reachX * sin(t * TWO_PI / 780f)
                            translationY = reachY * sin(t * TWO_PI / 1020f + 1.3f)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CalmChange(time) { t ->
                        Text(
                            t,
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 112.sp,
                            fontWeight = FontWeight.ExtraLight,
                            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (style == DimStyle.TimeAndInfo) {
                        val info = Modifier.fillMaxWidth()
                        CalmChange(ps.station?.name.orEmpty()) { name ->
                        Text(
                            name,
                            color = Color.White.copy(alpha = 0.34f),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = info,
                        )
                        }
                        Spacer(Modifier.height(4.dp))
                        CalmChange(ps.song) { song ->
                        Text(
                            song,
                            color = Color.White.copy(alpha = 0.26f),
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = info,
                        )
                        }
                    }
                }
            }
        }
    }
}

/** New text fades in with a slight rise while the old one fades out; slow enough to feel calm. */
@Composable
private fun CalmChange(value: String, content: @Composable (String) -> Unit) {
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

private const val TWO_PI = (2 * Math.PI).toFloat()
