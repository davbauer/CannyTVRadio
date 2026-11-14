package com.cannytvradio.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cannytvradio.data.Station
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/** Tiles per row everywhere; their width is computed from the screen, so rows always show whole tiles. */
const val COLUMNS = 4
val GAP = 22.dp
val TILE_WIDTH = 148.dp
/** Artwork is 1.42 times as wide as high, whatever the tile width. */
const val ART_RATIO = 1.42f
val EDGE = 28.dp
val INK = Color(0xFF0B0E16)

/**
 * Scroll only when the focused item would leave the visible area, and then just far enough to show it
 * with the page's edge spacing. With tiles sized to fit the row exactly, a step moves exactly one tile,
 * so no tile is ever cut in half. (Android TV's default slides whole rows on every press.)
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class CalmScroll(private val margin: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    private val glide: androidx.compose.animation.core.AnimationSpec<Float> = tween(140, easing = FastOutSlowInEasing)
    private val jump: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.snap()

    // Held arrow keys: jump each step, so a slow TV never chases a queue of half-finished animations.
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float>
        get() = if (Motion.holding) jump else glide

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            size > containerSize - 2 * margin -> offset - margin
            offset < margin -> offset - margin
            end > containerSize - margin -> end - (containerSize - margin)
            else -> 0f
        }
    }
}

/**
 * Whether the remote is moving. While keys come in quickly, new tiles show their plate and
 * load the logo only once input pauses (0.16 s): image loaders are the most expensive part
 * of a tile, and building them mid-scroll is what made fast scrolling stutter on this TV.
 */
object Motion {
    var settled by mutableStateOf(true)
        private set

    /** An arrow key is being held: scroll jumps straight to the target instead of animating. */
    @Volatile var holding = false
        private set
    private var job: kotlinx.coroutines.Job? = null

    fun keyPressed(scope: kotlinx.coroutines.CoroutineScope, repeat: Boolean) {
        holding = repeat
        settled = false
        job?.cancel()
        job = scope.launch {
            delay(160)
            holding = false
            settled = true
        }
    }
}

/** Station id that should take focus once it is on screen again. */
class FocusMemory {
    var id by mutableStateOf<String?>(null)
}

val LocalFocusMemory = staticCompositionLocalOf { FocusMemory() }

/** Press state of one focusable; plain fields, since nothing on screen depends on them. */
private class Press {
    var down = false
    var held = false
}

/**
 * Makes something focusable with the remote: OK clicks, holding OK long-clicks.
 * Only a press that started here counts, so the OK that opened a screen can't click on it.
 */
@Composable
fun Modifier.dpad(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
): Modifier {
    val press = remember { Press() }
    return this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { onFocusChange(it.isFocused) }
        .onKeyEvent { e ->
            if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return@onKeyEvent false
            when (e.type) {
                KeyEventType.KeyDown -> {
                    if (e.nativeKeyEvent.repeatCount == 0) {
                        press.down = true
                        press.held = false
                    }
                    if (e.nativeKeyEvent.repeatCount == 1 && press.down && onLongClick != null) {
                        press.held = true
                        onLongClick()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (press.down && !press.held) onClick()
                    press.down = false
                    press.held = false
                    true
                }
                else -> false
            }
        }
        .focusable()
}

/**
 * A focusable surface. Focus lifts it (scale read while drawing, so no re-layout)
 * and draws a single white frame.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(20.dp),
    focusedScale: Float = 1.08f,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    background: Color = Glass.panel,
    focusedBackground: Color = Glass.panelFocused,
    outline: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(if (focused) focusedScale else 1f, tween(110, easing = FastOutSlowInEasing), label = "scale")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .dpad(onClick, onLongClick, focusRequester) {
                if (focused != it) onFocusChange(it)
                focused = it
            }
            .clip(shape)
            .background(if (focused) focusedBackground else background)
            .then(if (outline && focused) Modifier.border(3.dp, Color.White, shape) else Modifier),
    ) { content(focused) }
}

private val PLATE = Color(0xFFEDEEF1)
private val INITIALS_BG = Brush.verticalGradient(listOf(Color(0xFF2E2F35), Color(0xFF1C1D21)))

/** Station artwork: the logo on a glossy plate, or initials on a color gradient. */
@Composable
fun Logo(station: Station?, modifier: Modifier = Modifier, corner: Dp = 16.dp) {
    val url = station?.favicon.orEmpty()
    var failed by remember(url) { mutableStateOf(url.isBlank()) }
    // Drawn once into its own image; scrolling and focus only move and scale that image.
    Box(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.clip(RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            station == null -> Box(Modifier.fillMaxSize().background(Glass.panel), contentAlignment = Alignment.Center) {
                Icon(Ico.Broadcast, null, tint = Glass.textFaint, modifier = Modifier.fillMaxSize(0.42f))
            }
            failed -> {
                val letters = remember(station.name) { initials(station.name) }
                Box(Modifier.fillMaxSize().background(INITIALS_BG), contentAlignment = Alignment.Center) {
                    Text(letters, color = Color.White.copy(alpha = 0.88f), fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp)
                }
            }
            else -> {
                Box(Modifier.fillMaxSize().background(PLATE))
                // A tile that appears mid-scroll waits for the remote to pause before building its image loader.
                var ready by remember(url) { mutableStateOf(Motion.settled) }
                if (!ready) {
                    LaunchedEffect(url) {
                        androidx.compose.runtime.snapshotFlow { Motion.settled }.first { it }
                        ready = true
                    }
                    return@Box
                }
                val context = LocalContext.current
                val request = remember(url) { ImageRequest.Builder(context).data(url).size(256).build() }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(0.82f),
                )
            }
        }
    }
}

private fun initials(name: String): String {
    val words = Station.normalize(name).split(" ").filter { it.isNotEmpty() && it !in setOf("radio", "the", "fm") }
    return when {
        words.isEmpty() -> name.take(2).uppercase()
        words.size == 1 -> words[0].take(3).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** Country name in the app's current language (follows a language switch right away). */
@Composable
fun countryName(code: String, fallback: String = ""): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    if (code.length != 2) return fallback
    // Looking names up in ICU is slow; every name is looked up once per language.
    return COUNTRY_NAMES.getOrPut("$code|${locale.language}") { Locale("", code).getDisplayCountry(locale) }.ifBlank { fallback }
}

private val COUNTRY_NAMES = java.util.concurrent.ConcurrentHashMap<String, String>()

/** Where the station is from: its region (clean, in the app's language), or else the country. */
@Composable
fun Station.metaLine(): String {
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    return com.cannytvradio.data.Regions.label(countryCode, state, language) ?: countryName(countryCode, country)
}

/** Artwork card that lifts on focus, with name and details underneath (tvOS style). */
@Composable
fun StationTile(
    station: Station,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(TILE_WIDTH),
    onLongClick: (() -> Unit)? = null,
    badge: String? = null,
    playing: Boolean = false,
    favorite: Boolean = false,
    focusRequester: FocusRequester? = null,
    moving: Boolean = false,
) {
    val memory = LocalFocusMemory.current
    val requester = focusRequester ?: remember { FocusRequester() }
    if (memory.id == station.id) {
        LaunchedEffect(Unit) {
            delay(80)
            runCatching { requester.requestFocus() }
            memory.id = null
        }
    }
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        if (focused) (if (moving) 1.14f else 1.08f) else 1f,
        tween(110, easing = FastOutSlowInEasing),
        label = "scale",
    )
    // The whole tile takes focus, so one scroll shows logo, name and place together; only the logo lifts.
    Column(modifier.dpad(onClick, onLongClick, requester) { focused = it }) {
        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier.fillMaxWidth().aspectRatio(ART_RATIO)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .clip(shape)
                .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier),
        ) {
            Logo(station, Modifier.fillMaxSize(), corner = 20.dp)
            if (moving) {
                Box(Modifier.fillMaxSize().background(INK.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Ico.Move, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
            if (badge != null) {
                Box(
                    Modifier.padding(8.dp).size(26.dp).clip(CircleShape).background(INK.copy(alpha = 0.78f)),
                    contentAlignment = Alignment.Center,
                ) { Text(badge, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
            if (favorite || playing) Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (favorite) Chiplet { Icon(Ico.HeartFilled, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                if (playing) Chiplet { Equalizer(active = true, height = 11.dp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(station.name, style = MaterialTheme.typography.titleMedium, color = if (focused) Color.White else Glass.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Text(station.metaLine(), style = MaterialTheme.typography.bodyMedium, color = Glass.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Chiplet(content: @Composable () -> Unit) {
    Box(
        Modifier.height(22.dp).clip(RoundedCornerShape(11.dp)).background(INK.copy(alpha = 0.78f)).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Placeholder in exactly the size of a [StationTile], with a light sweeping across. */
@Composable
fun SkeletonTile(modifier: Modifier = Modifier.width(TILE_WIDTH)) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x = t.animateFloat(-400f, 1400f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "x")
    val sweep = Modifier.drawBehind {
        drawRect(Color.White.copy(alpha = 0.06f))
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.09f), Color.Transparent),
                start = Offset(x.value, 0f),
                end = Offset(x.value + 300f, 120f),
            ),
        )
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(ART_RATIO).clip(RoundedCornerShape(20.dp)).then(sweep))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(0.75f).height(14.dp).clip(RoundedCornerShape(7.dp)).then(sweep))
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(11.dp).clip(RoundedCornerShape(6.dp)).then(sweep))
    }
}

/** A titled row of stations; `null` shows placeholders of the same size, so nothing jumps. */
@Composable
fun StationRow(
    title: String,
    stations: List<Station>?,
    onPlay: (Station) -> Unit,
    onLongClick: (Station) -> Unit,
    currentId: String?,
    favorites: Set<String>,
    numbered: Boolean = false,
    firstFocus: FocusRequester? = null,
) {
    androidx.compose.foundation.layout.BoxWithConstraints {
        val tile = Modifier.width((maxWidth - EDGE * 2 - GAP * (COLUMNS - 1)) / COLUMNS)
        Column {
        SectionTitle(title, Modifier.padding(start = EDGE))
        // Placeholders and tiles have the same size, so swapping them needs no transition.
        // The next tile to the right and left is built ahead, so moving along a row never builds one mid-frame.
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        val rowState = androidx.compose.foundation.lazy.rememberLazyListState(
            cacheWindow = remember { androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(ahead = 200.dp, behind = 200.dp) },
        )
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(start = EDGE, end = EDGE, top = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(GAP),
            userScrollEnabled = stations != null,
        ) {
            if (stations == null) {
                items(COLUMNS + 1, contentType = { "skeleton" }) { SkeletonTile(tile) }
            } else {
                itemsIndexed(stations, key = { _, s -> s.id }, contentType = { _, _ -> "station" }) { i, s ->
                    StationTile(
                        s,
                        onClick = { onPlay(s) },
                        modifier = tile,
                        onLongClick = { onLongClick(s) },
                        badge = if (numbered && i < 9) "${i + 1}" else null,
                        playing = s.id == currentId,
                        favorite = !numbered && s.id in favorites,
                        focusRequester = if (i == 0) firstFocus else null,
                    )
                }
            }
        }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Glass.text, modifier = modifier, maxLines = 1)
}

/**
 * Capsule for tabs, countries, regions and genres. Selected: soft white glass.
 * Focused: solid white with dark text.
 */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    height: Dp = 42.dp,
    /** Choices (genres, countries, regions) sit on a glass surface; tabs don't. */
    chip: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    FocusCard(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocusChange = {
            focused = it
            if (it) onFocus()
        },
        focusRequester = focusRequester,
        shape = RoundedCornerShape(50),
        focusedScale = 1.06f,
        outline = false,
        background = when {
            selected -> Color.White.copy(alpha = 0.2f)
            chip -> Color.White.copy(alpha = 0.07f)
            else -> Color.Transparent
        },
        focusedBackground = Color.White,
    ) {
        Row(
            Modifier.height(height).padding(horizontal = if (text.isEmpty()) 11.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = when {
                focused -> INK
                selected -> Glass.text
                else -> Glass.textDim
            }
            if (leading != null) {
                leading()
                Spacer(Modifier.width(9.dp))
            }
            if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            if (icon != null && text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            if (text.isNotEmpty()) {
                Text(
                    text,
                    color = color,
                    style = MaterialTheme.typography.titleMedium,
                    // Same weight in every state, so a pill never changes width and pushes its neighbours.
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Non-focusable info chip. */
@Composable
fun InfoChip(text: String, icon: ImageVector? = null) {
    Row(
        Modifier.height(34.dp).glass(RoundedCornerShape(50), tint = 0.3f).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Glass.textDim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = Glass.textDim, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

/** Round icon button. [active] tints it in the station color (a setting that is on). */
@Composable
fun IconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = Glass.text,
    active: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    badge: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Box(modifier) {
        FocusCard(
            onClick = { if (enabled) onClick() },
            onLongClick = onLongClick?.takeIf { enabled },
            shape = CircleShape,
            focusedScale = 1.14f,
            outline = false,
            background = if (active) Color.White.copy(alpha = 0.16f) else Color.Transparent,
            focusedBackground = Color.White,
            focusRequester = focusRequester,
            onFocusChange = { focused = it },
            modifier = Modifier.size(size),
        ) {
            Icon(
                icon,
                contentDescription,
                tint = when {
                    focused -> INK
                    !enabled -> Glass.textFaint
                    else -> tint
                },
                modifier = Modifier.align(Alignment.Center).size(size * 0.5f),
            )
        }
        if (badge != null) {
            Text(
                badge,
                color = INK,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(8.dp)).background(Color.White)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** The big white play button. A ring spins around it while connecting. */
@Composable
fun PlayButton(
    playing: Boolean,
    buffering: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    focusRequester: FocusRequester? = null,
    size: Dp = 68.dp,
) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (buffering) Spinner(size, 3.dp)
        FocusCard(
            onClick = onClick,
            shape = CircleShape,
            focusedScale = 1.1f,
            outline = false,
            background = Color.White.copy(alpha = 0.92f),
            focusedBackground = Color.White,
            focusRequester = focusRequester,
            modifier = Modifier.size(size - 12.dp),
        ) {
            AnimatedContent(
                targetState = playing || buffering,
                transitionSpec = {
                    (fadeIn(tween(160)) + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.6f))
                },
                label = "playIcon",
                modifier = Modifier.align(Alignment.Center),
            ) { on ->
                Icon(if (on) Ico.Pause else Ico.Play, contentDescription, tint = INK, modifier = Modifier.size((size - 12.dp) * 0.48f))
            }
        }
    }
}

/** On/off switch that slides, in the station color when on. */
@Composable
fun Switch(on: Boolean) {
    val accent = LocalAccent.current
    val knob = animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium), label = "knob")
    val track by animateColorAsState(if (on) Color.White else Color.White.copy(alpha = 0.18f), tween(200), label = "track")
    val knobColor by animateColorAsState(if (on) INK else Color.White, tween(200), label = "knobColor")
    Canvas(Modifier.size(width = 50.dp, height = 30.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val r = size.height / 2 - 3.dp.toPx()
        val x = size.height / 2 + (size.width - size.height) * knob.value
        drawCircle(knobColor, r, Offset(x, size.height / 2))
    }
}

/** Arc that grows, shrinks and turns, in the station color. */
@Composable
fun Spinner(size: Dp = 44.dp, stroke: Dp = 3.5.dp, color: Color = LocalAccent.current) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle = t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "angle")
    val sweep = t.animateFloat(
        30f, 280f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = angle.value }) {
        val s = sweep.value
        drawArc(color, -s / 2, s, useCenter = false, style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
    }
}

/** Four sound bars. They only move when [animated]; in lists they stay still, so idle screens draw nothing. */
@Composable
fun Equalizer(active: Boolean, height: Dp = 18.dp, color: Color = LocalAccent.current, animated: Boolean = false) {
    val rest = floatArrayOf(0.55f, 1f, 0.7f, 0.4f)
    val bars = if (animated && active) {
        val t = rememberInfiniteTransition(label = "eq")
        listOf(520, 380, 610, 450).mapIndexed { i, ms ->
            t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(ms), RepeatMode.Reverse), label = "bar$i")
        }
    } else null
    Canvas(Modifier.size(width = 18.dp, height = height)) {
        val w = 3.dp.toPx()
        val gap = (size.width - 4 * w) / 3
        for (i in 0 until 4) {
            val f = when {
                !active -> 0.25f
                bars != null -> bars[i].value
                else -> rest[i]
            }
            val h = size.height * f
            drawRoundRect(color, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** Centered text, or the spinner when [text] is null. */
@Composable
fun Message(text: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.Center) {
        if (text == null) Spinner() else Text(text, color = Glass.textDim, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A slim scrollbar on the right edge. Only drawn (never laid out), so it adds no work while scrolling.
 * [progress] returns position (0..1) and the visible share (0..1), or null when everything fits.
 */
fun Modifier.scrollbar(progress: () -> Pair<Float, Float>?): Modifier = drawWithContent {
    drawContent()
    val (position, visible) = progress() ?: return@drawWithContent
    val pad = 10.dp.toPx()
    val w = 4.dp.toPx()
    val x = size.width - w - 8.dp.toPx()
    val track = size.height - 2 * pad
    val thumb = (track * visible).coerceAtLeast(36.dp.toPx())
    drawRoundRect(Color.White.copy(alpha = 0.07f), Offset(x, pad), Size(w, track), CornerRadius(w / 2))
    drawRoundRect(Color.White.copy(alpha = 0.38f), Offset(x, pad + (track - thumb) * position), Size(w, thumb), CornerRadius(w / 2))
}

/**
 * Scroll position of a list in real pixels. Item heights are remembered once seen, unknown ones
 * use the average, so the thumb moves evenly even when items differ a lot in height.
 */
class ListScrollbar(private val state: androidx.compose.foundation.lazy.LazyListState) {
    private val heights = HashMap<Int, Int>()

    fun progress(): Pair<Float, Float>? {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (info.totalItemsCount == 0 || visible.isEmpty() || (!state.canScrollForward && !state.canScrollBackward)) return null
        visible.forEach { heights[it.index] = it.size }
        val gap = info.mainAxisItemSpacing
        val average = heights.values.average().toFloat()
        fun height(i: Int) = (heights[i]?.toFloat() ?: average) + gap
        val total = (0 until info.totalItemsCount).sumOf { height(it).toDouble() }.toFloat() +
            info.beforeContentPadding + info.afterContentPadding
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val first = visible.first()
        val scrolled = (0 until first.index).sumOf { height(it).toDouble() }.toFloat() - first.offset + info.beforeContentPadding
        val range = (total - viewport).coerceAtLeast(1f)
        return (scrolled / range).coerceIn(0f, 1f) to (viewport / total).coerceIn(0.08f, 1f)
    }
}

@androidx.compose.runtime.Composable
fun rememberScrollbar(state: androidx.compose.foundation.lazy.LazyListState) = remember(state) { ListScrollbar(state) }

/** Scroll position of a grid, by rows (all rows have the same height). */
fun androidx.compose.foundation.lazy.grid.LazyGridState.progress(): Pair<Float, Float>? {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (info.totalItemsCount == 0 || visible.isEmpty() || (!canScrollForward && !canScrollBackward)) return null
    val first = visible.first()
    val rowHeight = (first.size.height + info.mainAxisItemSpacing).toFloat()
    val rows = (info.totalItemsCount + COLUMNS - 1) / COLUMNS
    val total = rows * rowHeight + info.beforeContentPadding + info.afterContentPadding
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val scrolled = first.row * rowHeight - first.offset.y + info.beforeContentPadding
    val range = (total - viewport).coerceAtLeast(1f)
    return (scrolled / range).coerceIn(0f, 1f) to (viewport / total).coerceIn(0.08f, 1f)
}
