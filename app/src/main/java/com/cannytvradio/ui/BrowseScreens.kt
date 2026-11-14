package com.cannytvradio.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.cannytvradio.R
import com.cannytvradio.RadioApp
import com.cannytvradio.data.Station
import com.cannytvradio.data.primaryLocale
import com.cannytvradio.player.PlayerState

/** Five-column station grid; `null` shows placeholders in the same size. */
@Composable
private fun StationGrid(
    stations: List<Station>?,
    ps: PlayerState,
    favorites: Set<String>,
    onPlay: (Station) -> Unit,
    onLongClick: (Station) -> Unit,
    numbered: Boolean = false,
    footer: String? = null,
    moving: String? = null,
    firstFocus: FocusRequester? = null,
    state: LazyGridState = rememberGridState(),
    modifier: Modifier = Modifier,
) {
    val list = stations
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = state,
        contentPadding = PaddingValues(start = EDGE, end = EDGE, top = 14.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        userScrollEnabled = list != null,
        modifier = modifier.fillMaxSize().scrollbar { state.progress() },
    ) {
        if (list == null) {
            items(12, contentType = { "skeleton" }) { SkeletonTile(Modifier) }
        } else {
            itemsIndexed(list, key = { _, s -> s.id }, contentType = { _, _ -> "station" }) { i, s ->
                StationTile(
                    s,
                    onClick = { onPlay(s) },
                    onLongClick = { onLongClick(s) },
                    modifier = Modifier,
                    badge = if (numbered && i < 9) "${i + 1}" else null,
                    playing = s.id == ps.station?.id,
                    favorite = !numbered && s.id in favorites,
                    focusRequester = if (i == 0) firstFocus else null,
                    moving = s.id == moving,
                )
            }
            if (footer != null) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                    Text(footer, color = Glass.textFaint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

/** Grid state that builds the next row of tiles ahead (Compose 1.9 cache window). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun rememberGridState(): LazyGridState =
    rememberLazyGridState(cacheWindow = remember { androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(ahead = 260.dp, behind = 120.dp) })

@Composable
private fun rememberFavoriteIds(app: RadioApp): Set<String> {
    val favorites by app.repo.favorites.collectAsState()
    return remember(favorites) { favorites.map { it.id }.toSet() }
}

private val GENRES = listOf(
    "pop" to R.string.g_pop, "rock" to R.string.g_rock, "news" to R.string.g_news, "jazz" to R.string.g_jazz,
    "classical" to R.string.g_classical, "electronic" to R.string.g_electronic, "chillout" to R.string.g_chillout,
    "80s" to R.string.g_80s, "90s" to R.string.g_90s, "oldies" to R.string.g_oldies, "schlager" to R.string.g_schlager,
    "hip hop" to R.string.g_hiphop, "country" to R.string.g_country, "talk" to R.string.g_talk,
    "sport" to R.string.g_sport, "kids" to R.string.g_kids,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(app: RadioApp, ps: PlayerState, actions: Actions) {
    val model = app.browse
    val ui = LocalUi.current
    val accent = LocalAccent.current
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val query by model.query.collectAsState()
    val results by model.results.collectAsState()
    val favorites = rememberFavoriteIds(app)
    var editing by remember { mutableStateOf(false) }
    val cardFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    fun stopEditing() {
        keyboard?.hide()
        editing = false
        runCatching { cardFocus.requestFocus() }
    }
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    }
    DisposableEffect(Unit) { onDispose { ui.typing = false } }

    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
    }
    val canVoice = remember { voiceIntent.resolveActivity(context.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(model::search)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = EDGE, end = EDGE, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // The field is a button first: focus only highlights it, OK opens the keyboard.
            FocusCard(
                onClick = { editing = true },
                focusRequester = cardFocus,
                shape = RoundedCornerShape(50),
                focusedScale = 1.02f,
                background = Glass.panel,
                focusedBackground = Color.White.copy(alpha = 0.18f),
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ico.Search, null, tint = Glass.textDim, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = Glass.textFaint, fontSize = 20.sp)
                        BasicTextField(
                            value = query,
                            onValueChange = model::search,
                            singleLine = true,
                            textStyle = TextStyle(color = Glass.text, fontSize = 20.sp),
                            cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { stopEditing() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(fieldFocus)
                                .focusProperties { canFocus = editing }
                                .onPreviewKeyEvent { e ->
                                    // Back or Down leave the keyboard and return to the field.
                                    val leave = e.key == Key.Back || e.key == Key.DirectionDown || e.key == Key.DirectionUp
                                    if (leave && e.type == KeyEventType.KeyUp) stopEditing()
                                    leave
                                }
                                .onFocusChanged {
                                    ui.typing = it.isFocused
                                    if (!it.isFocused) editing = false
                                },
                        )
                    }
                }
            }
            if (canVoice) {
                Spacer(Modifier.width(12.dp))
                IconButton(Ico.Mic, stringResource(R.string.voice_search), { voice.launch(voiceIntent) }, size = 52.dp)
            }
        }
        val lane = results
        when {
            lane == null -> {
                SectionTitle(stringResource(R.string.genres), Modifier.padding(start = EDGE, top = 22.dp, bottom = 12.dp))
                FlowRow(
                    Modifier.padding(horizontal = EDGE),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GENRES.forEach { (tag, label) ->
                        Pill(stringResource(label), selected = false, onClick = { model.search(tag) }, chip = true)
                    }
                }
            }
            lane.stations?.isEmpty() == true -> Message(stringResource(R.string.no_results, query))
            else -> StationGrid(lane.stations, ps, favorites, actions::play, actions::toggleFavorite)
        }
    }
}

@Composable
fun RegionsScreen(app: RadioApp, ps: PlayerState, actions: Actions) {
    val model = app.browse
    val prefs by app.settings.prefs.collectAsState()
    val countries by model.countries.collectAsState()
    val selected by model.country.collectAsState()
    val regions by model.regions.collectAsState()
    val region by model.region.collectAsState()
    val stations by model.regionStations.collectAsState()
    val favorites = rememberFavoriteIds(app)
    LaunchedEffect(Unit) { model.openRegions() }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.primaryLocale()

    // Home country first, then the biggest ones, named in the TV's language.
    val ordered = remember(countries, prefs.home) {
        countries.filter { it.code == prefs.home } + countries.filter { it.code != prefs.home }.take(80)
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            Modifier.height(58.dp),
            contentPadding = PaddingValues(horizontal = EDGE, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(ordered, key = { it.code }) { c ->
                Pill(
                    countryName(c.code, c.name),
                    selected = c.code == selected,
                    chip = true,
                    onClick = { model.selectCountry(c.code) },
                    onLongClick = {
                        app.settings.update { it.copy(homeCountry = c.code) }
                        actions.toast(app.getString(R.string.home_set, java.util.Locale("", c.code).getDisplayCountry(locale)))
                    },
                )
            }
        }
        // Always the same height, so the grid doesn't jump when regions arrive.
        LazyRow(
            Modifier.height(58.dp),
            contentPadding = PaddingValues(horizontal = EDGE, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item("all") { Pill(stringResource(R.string.regions_all), selected = region == null, onClick = { model.selectRegion(null) }, chip = true) }
            items(regions, key = { it.key }) { r ->
                val label = r.code?.let { com.cannytvradio.data.Regions.name(it, locale.language) } ?: r.name
                Pill(label, selected = r.key == region?.key, onClick = { model.selectRegion(r) }, chip = true)
            }
        }
        if (stations.failed) Message(stringResource(R.string.load_failed))
        else StationGrid(stations.stations, ps, favorites, actions::play, actions::toggleFavorite)
    }
}

/**
 * Favorites, numbered like radio presets. Hold OK on one to pick it up, move it with the arrows
 * (it moves right away), OK puts it down, holding OK again removes it.
 */
@Composable
fun FavoritesScreen(app: RadioApp, ps: PlayerState, actions: Actions) {
    val favorites by app.repo.favorites.collectAsState()
    val ui = LocalUi.current
    var moving by remember { mutableStateOf<String?>(null) }
    val state = rememberGridState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    LaunchedEffect(favorites.isNotEmpty()) {
        if (favorites.isNotEmpty() && !ui.initialFocusDone) {
            repeat(20) {
                delay(100)
                if (runCatching { first.requestFocus() }.isSuccess) {
                    ui.initialFocusDone = true
                    return@LaunchedEffect
                }
            }
        }
    }

    if (favorites.isEmpty()) {
        Message(stringResource(R.string.favorites_empty))
        return
    }
    Box(Modifier.fillMaxSize()) {
        StationGrid(
            favorites, ps, emptySet(),
            onPlay = { s -> if (moving == s.id) moving = null else if (moving == null) actions.play(s) },
            onLongClick = { s ->
                if (moving == s.id) {
                    moving = null
                    actions.toggleFavorite(s)
                } else {
                    moving = s.id
                }
            },
            numbered = true,
            footer = stringResource(R.string.favorites_hint),
            moving = moving,
            firstFocus = first,
            state = state,
            modifier = Modifier.onPreviewKeyEvent { e ->
                val id = moving ?: return@onPreviewKeyEvent false
                val by = when (e.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    Key.DirectionUp -> -COLUMNS
                    Key.DirectionDown -> COLUMNS
                    Key.Back -> {
                        if (e.type == KeyEventType.KeyUp) moving = null
                        return@onPreviewKeyEvent true
                    }
                    else -> return@onPreviewKeyEvent false
                }
                if (e.type == KeyEventType.KeyDown) {
                    app.repo.moveFavorite(id, by)
                    val index = app.repo.favorites.value.indexOfFirst { it.id == id }
                    val shown = state.layoutInfo.visibleItemsInfo.map { it.index }
                    if (index >= 0 && index !in shown) scope.launch { state.animateScrollToItem(index) }
                }
                true
            },
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = moving != null,
            enter = fadeIn() + androidx.compose.animation.slideInVertically { -it },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
        ) {
            Text(
                stringResource(R.string.move_hint),
                color = Glass.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.glass(RoundedCornerShape(50), tint = 0.85f).padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
