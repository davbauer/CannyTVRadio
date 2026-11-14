package com.cannytvradio.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.cannytvradio.data.Repository
import com.cannytvradio.data.Station
import com.cannytvradio.data.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Song(val title: String, val at: Long)

data class PlayerState(
    val station: Station? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /** Current song as sent by the station, "Artist - Title". */
    val song: String = "",
    /** Songs heard on this station, newest first. */
    val songs: List<Song> = emptyList(),
    val streamIndex: Int = 0,
    val streamCount: Int = 0,
    val streamUrl: String = "",
    /** What is actually decoding, for example "AAC · 128 kbps · 48 kHz · Stereo". */
    val format: String = "",
    val genre: String = "",
    val failed: Boolean = false,
    /** Epoch millis when the sleep timer stops playback, 0 when off. */
    val sleepAt: Long = 0,
)

/**
 * Owns the one ExoPlayer of the app. A station is a list of streams: when one fails or
 * stalls, the next one starts; when all fail, it fetches fresh streams and tries again,
 * and it reconnects by itself when the network comes back.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val repo: Repository,
    private val scope: CoroutineScope,
    /** Equalizer and volume leveling, applied to everything that plays. */
    val sound: SoundProcessor = SoundProcessor(),
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    val player: ExoPlayer

    private var streams: List<String> = emptyList()
    private var index = 0
    private var rounds = 0
    private var stallJob: Job? = null
    private var backupJob: Job? = null
    private var sleepJob: Job? = null

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
        // Smart buffering for live radio: start after 1.5 s, then keep filling up to 60 s.
        // After a dropout wait for 4 s of audio, so a weak network doesn't stutter in a loop.
        val buffering = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_500, 4_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // Short network drops are retried quietly on the same stream before we switch to a backup.
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        // 16-bit output keeps our own sound processing in the audio path.
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(sound)).build()
        }
        player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(buffering)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val onNetwork = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    val s = _state.value
                    if (s.station != null && (s.failed || (player.playWhenReady && !player.isPlaying))) restart()
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(onNetwork)
        } else {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(request, onNetwork)
        }
    }

    fun play(station: Station) {
        backupJob?.cancel()
        fadeJob?.cancel()
        fadeInWhenPlaying = false
        player.volume = 1f
        rounds = 0
        _state.value = PlayerState(station = station, buffering = true, sleepAt = _state.value.sleepAt)
        repo.addRecent(station)
        if (station.source == Station.Source.RadioBrowser) scope.launch { repo.browser.click(station.id) }

        if (station.streams.isNotEmpty()) {
            streams = station.streams
            index = 0
            start()
        }
        // Look up backup streams in the background; a TuneIn result has none until this finishes.
        backupJob = scope.launch {
            val full = runCatching { repo.withBackups(station) }.getOrNull()
            if (_state.value.station?.id != station.id) return@launch
            if (full == null || full.streams.isEmpty()) {
                if (streams.isEmpty() || station.streams.isEmpty()) fail()
                return@launch
            }
            repo.refreshStored(full)
            if (station.streams.isEmpty()) {
                streams = full.streams
                index = 0
                _state.update { it.copy(station = full) }
                start()
            } else {
                streams = (streams.take(index + 1) + full.streams).distinct()
                _state.update { it.copy(station = full, streamCount = streams.size) }
            }
        }
    }

    /** Play/pause, the same for the remote, headphones, Bluetooth, Google TV and the screen. */
    fun toggle() {
        if (player.playWhenReady) pause() else resume()
    }

    /** Fades out over 0.2 s, then pauses. */
    fun pause() {
        if (!player.playWhenReady) return
        pausedAt = System.currentTimeMillis()
        fadeJob?.cancel()
        fadeJob = scope.launch {
            fadeVolume(0f, FADE_PAUSE_MS)
            player.pause()
        }
    }

    /**
     * After a short pause the buffered audio simply continues; after a longer one the stream
     * reconnects, so you hear what is on air now. Either way the sound fades in.
     */
    fun resume() {
        if (_state.value.station == null) return
        fadeJob?.cancel()
        rounds = 0
        if (streams.isEmpty()) _state.value.station?.let { play(it); return }
        player.volume = 0f
        val shortPause = System.currentTimeMillis() - pausedAt < QUICK_RESUME_MS
        if (shortPause && player.playbackState == Player.STATE_READY) {
            player.play()
            fadeJob = scope.launch { fadeVolume(1f, FADE_IN_MS) }
        } else {
            fadeInWhenPlaying = true
            start()
        }
    }

    private var pausedAt = 0L
    private var fadeJob: Job? = null
    private var fadeInWhenPlaying = false

    private suspend fun fadeVolume(target: Float, ms: Long) {
        val from = player.volume
        val steps = 12
        for (i in 1..steps) {
            player.volume = from + (target - from) * i / steps
            delay(ms / steps)
        }
    }

    /** Next or previous favorite, like the channel buttons on a radio. */
    fun skip(by: Int) {
        val favorites = repo.favorites.value
        if (favorites.isEmpty()) return
        val i = favorites.indexOfFirst { it.id == _state.value.station?.id }
        val target = if (i < 0) (if (by > 0) 0 else favorites.lastIndex) else (i + by).mod(favorites.size)
        play(favorites[target])
    }

    /** Favorite number [n] (1-based), for the number keys on the remote. */
    fun preset(n: Int) {
        repo.favorites.value.getOrNull(n - 1)?.let(::play)
    }

    /** Steps through off, 15, 30, 60 and 90 minutes. */
    fun cycleSleepTimer() {
        val left = _state.value.sleepAt.let { if (it == 0L) 0 else ((it - System.currentTimeMillis()) / 60_000).toInt() + 1 }
        val next = SLEEP_STEPS.firstOrNull { it > left } ?: 0
        setSleepTimer(next)
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        player.volume = 1f
        if (minutes == 0) {
            _state.update { it.copy(sleepAt = 0) }
            return
        }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepAt = at) }
        sleepJob = scope.launch {
            delay((at - System.currentTimeMillis() - FADE_MS).coerceAtLeast(0))
            // Fade out over the last 30 seconds instead of cutting off.
            for (step in 30 downTo 0) {
                player.volume = step / 30f
                delay(FADE_MS / 30)
            }
            player.pause()
            player.volume = 1f
            _state.update { it.copy(sleepAt = 0) }
        }
    }

    private fun start() {
        val station = _state.value.station ?: return
        val url = streams.getOrNull(index) ?: return fail()
        val artwork = station.favicon.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaId(station.id)
            .apply {
                if (".m3u8" in url || (station.hls && url == station.streams.firstOrNull())) setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setStation(station.name)
                    .setTitle(station.name)
                    .setArtist(listOf(station.state, station.country).filter { it.isNotBlank() }.joinToString(", "))
                    .setArtworkUri(artwork)
                    .build(),
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
        _state.update {
            it.copy(streamIndex = index, streamCount = streams.size, streamUrl = url, failed = false, buffering = true)
        }
    }

    private fun restart() {
        rounds = 0
        index = 0
        start()
    }

    /** Moves to the next stream; after the last one, fetches fresh streams and starts over (3 rounds). */
    private fun next() {
        stallJob?.cancel()
        if (index + 1 < streams.size) {
            index++
            start()
            return
        }
        rounds++
        if (rounds > MAX_ROUNDS) return fail()
        _state.update { it.copy(buffering = true) }
        scope.launch {
            delay(2_000L * rounds)
            val station = _state.value.station ?: return@launch
            runCatching { repo.withBackups(station) }.getOrNull()
                ?.takeIf { it.streams.isNotEmpty() }
                ?.let {
                    streams = it.streams
                    repo.refreshStored(it)
                }
            index = 0
            start()
        }
    }

    private fun fail() {
        stallJob?.cancel()
        player.stop()
        _state.update { it.copy(failed = true, buffering = false, playing = false) }
    }

    private fun onSong(raw: String?) {
        // Stations often append their slogan: "Artist - Title | FM4 Sounds Like FM4" -> "Artist - Title".
        val title = raw?.split(" | ")?.first()?.trim()?.trim('-', ' ').orEmpty()
        val station = _state.value.station
        if (title.length < 3 || title.equals(station?.name, ignoreCase = true)) return
        _state.update { s ->
            val songs = if (s.songs.firstOrNull()?.title == title) s.songs
            else (listOf(Song(title, System.currentTimeMillis())) + s.songs).take(MAX_SONGS)
            s.copy(song = title, songs = songs)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = next()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
            if (isPlaying) {
                rounds = 0
                if (fadeInWhenPlaying || player.volume < 1f && fadeJob?.isActive != true && sleepJob?.isActive != true) {
                    fadeInWhenPlaying = false
                    fadeJob = scope.launch { fadeVolume(1f, FADE_IN_MS) }
                }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            _state.update { it.copy(buffering = state == Player.STATE_BUFFERING) }
            stallJob?.cancel()
            when (state) {
                // Some servers accept the connection and then send nothing.
                Player.STATE_BUFFERING -> if (player.playWhenReady) stallJob = scope.launch {
                    delay(STALL_MS)
                    if (player.playbackState == Player.STATE_BUFFERING) next()
                }
                // A live stream never ends on purpose; the server dropped us.
                Player.STATE_ENDED -> if (player.playWhenReady) start()
                else -> Unit
            }
        }

        override fun onMetadata(metadata: Metadata) {
            var artist = ""
            var title = ""
            for (i in 0 until metadata.length()) {
                when (val e = metadata.get(i)) {
                    is IcyInfo -> onSong(e.title)
                    is IcyHeaders -> e.genre?.let { g -> _state.update { it.copy(genre = g) } }
                    is TextInformationFrame -> when (e.id) {
                        "TIT2" -> title = e.values.firstOrNull().orEmpty()
                        "TPE1" -> artist = e.values.firstOrNull().orEmpty()
                    }
                    else -> Unit
                }
            }
            if (title.isNotBlank()) onSong(if (artist.isNotBlank()) "$artist - $title" else title)
        }

        override fun onTracksChanged(tracks: Tracks) {
            val f = player.audioFormat ?: return
            val station = _state.value.station
            val codec = when (f.sampleMimeType) {
                MimeTypes.AUDIO_MPEG -> "MP3"
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_OPUS -> "Opus"
                MimeTypes.AUDIO_VORBIS -> "Ogg Vorbis"
                MimeTypes.AUDIO_FLAC -> "FLAC"
                else -> station?.codec.orEmpty()
            }
            val kbps = if (f.bitrate > 0) f.bitrate / 1000 else station?.bitrate ?: 0
            val parts = listOfNotNull(
                codec.ifBlank { null },
                kbps.takeIf { it > 0 }?.let { "$it kbps" },
                f.sampleRate.takeIf { it > 0 }?.let { "%.1f kHz".format(it / 1000.0).replace(".0 ", " ") },
                when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; in 3..8 -> "${f.channelCount} ch"; else -> null },
            )
            _state.update { it.copy(format = parts.joinToString(" · ")) }
        }
    }

    init {
        player.addListener(listener)
    }

    private companion object {
        val SLEEP_STEPS = listOf(15, 30, 60, 90)
        const val FADE_MS = 30_000L
        const val STALL_MS = 15_000L
        const val MAX_ROUNDS = 3
        const val MAX_SONGS = 20
        const val FADE_PAUSE_MS = 200L
        const val FADE_IN_MS = 400L
        const val QUICK_RESUME_MS = 20_000L
    }
}
