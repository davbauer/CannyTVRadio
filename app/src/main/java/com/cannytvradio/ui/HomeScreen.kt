package com.cannytvradio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cannytvradio.R
import com.cannytvradio.RadioApp
import com.cannytvradio.data.Station
import com.cannytvradio.player.PlayerState
import kotlinx.coroutines.delay

private data class Shelf(val key: String, val title: String, val stations: List<Station>?, val numbered: Boolean = false)

@Composable
fun HomeScreen(app: RadioApp, ps: PlayerState, actions: Actions) {
    val model = app.browse
    val ui = LocalUi.current
    val favorites by app.repo.favorites.collectAsState()
    val recent by app.repo.recent.collectAsState()
    val prefs by app.settings.prefs.collectAsState()
    val popular by model.popular.collectAsState()
    val trending by model.trending.collectAsState()
    val world by model.world.collectAsState()

    LaunchedEffect(prefs.home) { model.loadHome() }
    // Nothing reachable and nothing saved: try again every 20 seconds.
    val allFailed = popular.failed && trending.failed && world.failed
    LaunchedEffect(allFailed) {
        while (allFailed) {
            delay(20_000)
            model.loadHome(force = true)
        }
    }

    val country = countryName(prefs.home)
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }
    val recentOnly = recent.filterNot { it.id in favoriteIds }.take(12)
    val trendingNew = trending.stations?.let { list ->
        val shown = popular.stations.orEmpty().take(15).map { it.id }.toSet()
        list.filterNot { it.id in shown }
    }
    val shelves = listOfNotNull(
        favorites.takeIf { it.isNotEmpty() }?.let { Shelf("fav", stringResource(R.string.favorites), it, numbered = true) },
        recentOnly.takeIf { it.isNotEmpty() }?.let { Shelf("recent", stringResource(R.string.recent), it) },
        Shelf("popular", stringResource(R.string.popular_in, country), popular.stations).takeUnless { popular.failed },
        Shelf("trending", stringResource(R.string.trending_in, country), trendingNew).takeUnless { trending.failed },
        Shelf("world", stringResource(R.string.worldwide), world.stations).takeUnless { world.failed },
    )

    val first = remember { FocusRequester() }
    // Rows within a row's height below and above the screen are built ahead, in idle moments,
    // so scrolling never has to build a row mid-frame (Compose 1.9 cache window).
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    val listState = rememberLazyListState(cacheWindow = remember { LazyLayoutCacheWindow(ahead = 280.dp, behind = 140.dp) })
    val firstReady = shelves.firstOrNull()?.stations?.isNotEmpty() == true
    LaunchedEffect(firstReady) {
        if (firstReady && !ui.initialFocusDone) {
            // The row may need a few frames before it can take focus.
            repeat(20) {
                delay(100)
                if (runCatching { first.requestFocus() }.isSuccess) {
                    ui.initialFocusDone = true
                    return@LaunchedEffect
                }
            }
        }
    }

    if (shelves.isEmpty()) {
        Message(stringResource(R.string.load_failed))
        return
    }
    val bar = rememberScrollbar(listState)
    LazyColumn(
        Modifier.fillMaxSize().scrollbar { bar.progress() },
        state = listState,
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(shelves, key = { it.key }) { shelf ->
            StationRow(
                title = shelf.title,
                stations = shelf.stations,
                onPlay = actions::play,
                onLongClick = actions::toggleFavorite,
                currentId = ps.station?.id,
                favorites = favoriteIds,
                numbered = shelf.numbered,
                firstFocus = if (shelf === shelves.first()) first else null,
            )
        }
    }
}
