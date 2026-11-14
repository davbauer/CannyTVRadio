package com.cannytvradio.ui

import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cannytvradio.R
import com.cannytvradio.RadioApp
import com.cannytvradio.player.PlayerState
import java.util.Date

/** Everything we know about the station that is playing. The controls stay in the bar below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowPlayingScreen(app: RadioApp, ps: PlayerState, actions: Actions) {
    val station = ps.station
    if (station == null) {
        Message(stringResource(R.string.pick_station))
        return
    }
    val accent = LocalAccent.current
    val similar by app.browse.similar.collectAsState()
    val favorites by app.repo.favorites.collectAsState()
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }
    LaunchedEffect(station.id) { app.browse.loadSimilar(station) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp)) {
        Row(Modifier.padding(horizontal = EDGE), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(212.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(212.dp).clip(RoundedCornerShape(40.dp))
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.5f), Color.Transparent))),
                )
                Logo(station, Modifier.size(184.dp), corner = 24.dp)
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Equalizer(active = ps.playing, height = 14.dp, animated = true)
                    Spacer(Modifier.width(10.dp))
                    Text(statusText(ps), color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    station.name,
                    color = Glass.text,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
                val place = listOf(
                    com.cannytvradio.data.Regions.label(station.countryCode, station.state, language).orEmpty(),
                    countryName(station.countryCode, station.country),
                    station.language.split(",").first().trim().replaceFirstChar { it.titlecase() },
                ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                Text(place, color = Glass.textDim, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Spacer(Modifier.height(14.dp))
                AnimatedContent(
                    targetState = ps.song,
                    transitionSpec = { (fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 }) togetherWith fadeOut(tween(150)) },
                    label = "song",
                ) { song ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
                        androidx.compose.material3.Icon(Ico.Music, null, tint = Glass.textDim, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            song.ifBlank { stringResource(R.string.no_title) },
                            color = if (song.isBlank()) Glass.textDim else Glass.text,
                            fontSize = if (song.isBlank()) 17.sp else 22.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (station.bitrate >= 192) InfoChip("HD", Ico.Waves)
                    val genres = ps.genre.takeIf { it.isNotBlank() && it.length < 30 }?.let { listOf(it) } ?: station.tags.take(3)
                    genres.forEach { InfoChip(it.replaceFirstChar { c -> c.titlecase() }) }
                    host(station.homepage)?.let { InfoChip(it, Ico.Globe) }
                }
            }
        }

        val earlier = ps.songs.drop(if (ps.songs.firstOrNull()?.title == ps.song) 1 else 0).take(4)
        if (earlier.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.recent_songs), Modifier.padding(start = EDGE, bottom = 8.dp))
            val context = LocalContext.current
            Column(Modifier.padding(horizontal = EDGE), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                earlier.forEach { song ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            DateFormat.getTimeFormat(context).format(Date(song.at)),
                            color = Glass.textFaint,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(64.dp),
                        )
                        Text(song.title, color = Glass.textDim, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (similar.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            StationRow(
                title = stringResource(R.string.similar),
                stations = similar,
                onPlay = actions::play,
                onLongClick = actions::toggleFavorite,
                currentId = station.id,
                favorites = favoriteIds,
            )
        }
    }
}

@Composable
private fun statusText(ps: PlayerState): String = when {
    ps.failed -> stringResource(R.string.failed_short)
    ps.buffering -> stringResource(R.string.connecting)
    ps.playing -> stringResource(R.string.live)
    else -> stringResource(R.string.paused)
}.uppercase()

private fun host(url: String): String? =
    runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }
