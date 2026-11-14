package com.cannytvradio.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

class Repository(
    context: Context,
    val http: Http,
    val browser: RadioBrowser,
    val tuneIn: TuneIn,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val favoritesFile = File(context.filesDir, "favorites.json")
    private val recentFile = File(context.filesDir, "recent.json")

    private val _favorites = MutableStateFlow(load(favoritesFile))
    val favorites: StateFlow<List<Station>> = _favorites.asStateFlow()

    private val _recent = MutableStateFlow(load(recentFile))
    val recent: StateFlow<List<Station>> = _recent.asStateFlow()

    /** True while lists come from the saved copy because no directory server answers. */
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val memory = ConcurrentHashMap<String, Pair<Long, List<Station>>>()

    // Favorites and recent stations

    fun isFavorite(id: String) = _favorites.value.any { it.id == id }

    fun toggleFavorite(station: Station) {
        if (isFavorite(station.id)) {
            saveFavorites(_favorites.value.filterNot { it.id == station.id })
        } else {
            saveFavorites(_favorites.value + station)
            // Collect backup streams right away, so the favorite still plays if its main URL dies.
            scope.launch { refreshStored(withBackups(station)) }
        }
    }

    fun moveFavorite(id: String, by: Int) {
        val list = _favorites.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + by).coerceIn(0, list.lastIndex)
        list.add(to, list.removeAt(from))
        saveFavorites(list)
    }

    fun addRecent(station: Station) {
        val list = (listOf(station) + _recent.value.filterNot { it.id == station.id }).take(24)
        _recent.value = list
        save(recentFile, list)
    }

    /** Replaces the stored copy of a station (favorite or recent) with fresher data. */
    fun refreshStored(station: Station) {
        if (isFavorite(station.id)) {
            saveFavorites(_favorites.value.map { if (it.id == station.id) station else it })
        }
        if (_recent.value.any { it.id == station.id }) {
            _recent.value = _recent.value.map { if (it.id == station.id) station else it }
            save(recentFile, _recent.value)
        }
    }

    private fun saveFavorites(list: List<Station>) {
        _favorites.value = list
        save(favoritesFile, list)
    }

    // Lists from the directory

    suspend fun list(path: String): List<Station> {
        memory[path]?.let { (at, value) -> if (System.currentTimeMillis() - at < CACHE_MS) return value }
        val r = browser.stations(path)
        _offline.value = r.stale
        if (!r.stale) memory[path] = System.currentTimeMillis() to r.value
        return r.value
    }

    suspend fun popularAtHome() = list(browser.byCountry(settings.prefs.value.home, limit = 40))

    suspend fun trendingAtHome() = list(browser.byCountry(settings.prefs.value.home, order = "clicktrend", limit = 30))

    suspend fun worldwide() = list(browser.top(40))

    suspend fun countries() = browser.countries().also { _offline.value = it.stale }.value

    suspend fun regions(countryName: String, countryCode: String) =
        browser.states(countryName, countryCode).also { _offline.value = it.stale }.value

    /** Stations of a whole country, or of one region across all its spellings, most played first. */
    suspend fun region(code: String, region: Region?): List<Station> = coroutineScope {
        if (region == null) return@coroutineScope list(browser.byCountry(code, limit = 100))
        region.variants
            .map { v -> async { runCatching { list(browser.byState(code, v)) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten()
            .distinctBy { it.id }
            .sortedByDescending { it.clicks }
            .take(150)
    }

    suspend fun similar(station: Station): List<Station> {
        val tag = station.tags.firstOrNull() ?: return emptyList()
        return runCatching {
            list(browser.byTag(tag, station.countryCode.ifEmpty { null }, 20))
        }.getOrDefault(emptyList()).filter { it.key != station.key }
    }

    // Search

    /**
     * Searches both directories at once and ranks the merged result:
     * name matches first, then popularity, with a boost for favorites and the home country.
     * Umlauts and their spelled-out forms match each other ("oe3" finds "Ö3").
     */
    suspend fun search(raw: String): List<Station> = coroutineScope {
        val q = raw.trim()
        if (q.length < 2) return@coroutineScope emptyList()

        val byName = variants(q).map { v -> async { runCatching { list(browser.byName(v)) }.getOrDefault(emptyList()) } }
        val byTag = async { runCatching { list(browser.byTag(q.lowercase(), null, 30)) }.getOrDefault(emptyList()) }
        val fromTuneIn = async { runCatching { tuneIn.search(q) }.getOrDefault(emptyList()) }

        val nq = Station.normalize(q)
        val mine = (_favorites.value + _recent.value).filter { Station.normalize(it.name).contains(nq) }

        val merged = merge(mine + byName.awaitAll().flatten() + byTag.await() + fromTuneIn.await())
        val home = settings.prefs.value.home
        merged.sortedByDescending { score(it, nq, home) }.take(60)
    }

    private fun score(s: Station, nq: String, home: String): Double {
        val n = Station.normalize(s.name)
        val words = n.split(" ")
        val text = when {
            n == nq -> 100.0
            n.startsWith(nq) -> 70.0
            words.any { it.startsWith(nq) } -> 55.0
            n.contains(nq) -> 40.0
            nq.split(" ").all { n.contains(it) } -> 30.0
            s.tags.any { Station.normalize(it).contains(nq) } -> 20.0
            else -> 0.0
        }
        return text + ln(s.clicks + 1.0) * 6 + ln(s.votes + 1.0) * 3 +
            (if (s.countryCode.equals(home, true)) 15 else 0) +
            (if (isFavorite(s.id)) 40 else 0) -
            (if (!s.ok) 40 else 0) -
            (if (s.source == Station.Source.TuneIn) 5 else 0)
    }

    /** One entry per station name; a TuneIn twin of a directory station is folded into it. */
    private fun merge(all: List<Station>): List<Station> =
        all.groupBy { it.key }.map { (_, same) ->
            val best = same.filter { it.source == Station.Source.RadioBrowser }.maxByOrNull { it.clicks } ?: same.first()
            val tuneIn = same.firstOrNull { it.source == Station.Source.TuneIn }
            if (tuneIn != null && best !== tuneIn) {
                best.copy(tuneInId = best.tuneInId.ifEmpty { tuneIn.tuneInId }, subtitle = best.subtitle.ifEmpty { tuneIn.subtitle })
            } else best
        }

    private fun variants(q: String): List<String> {
        val l = q.lowercase()
        val spelled = l.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        val umlauts = l.replace("ae", "ä").replace("oe", "ö").replace("ue", "ü")
        return listOf(q, spelled, umlauts).distinctBy { it.lowercase() }
    }

    // Streams and backups

    /**
     * Returns the station with every working stream we can find, in play order:
     * the directory's current URL, the same station listed under other entries,
     * then TuneIn's streams, then whatever was stored before.
     */
    suspend fun withBackups(s: Station): Station = coroutineScope {
        val fresh = if (s.source == Station.Source.RadioBrowser) async {
            runCatching { browser.stations(browser.byUuids(listOf(s.id))).value.firstOrNull() }.getOrNull()
        } else null
        val twins = async {
            runCatching { browser.stations(browser.byName(s.name, 25)).value }.getOrDefault(emptyList())
                .filter { it.id != s.id && sameStation(it.name, s.name) }
        }
        val tuneInId = s.tuneInId.ifEmpty {
            runCatching { tuneIn.search(s.name).firstOrNull { sameStation(it.name, s.name) }?.tuneInId }.getOrNull() ?: ""
        }
        val tuneInStreams = if (tuneInId.isNotEmpty()) runCatching { tuneIn.streams(tuneInId) }.getOrDefault(emptyList()) else emptyList()

        val base = fresh?.await() ?: s
        val others = twins.await()
        val directory = when (settings.prefs.value.streamOrder) {
            StreamOrder.Quality -> (listOf(base) + others).sortedWith(
                compareByDescending<Station> { it.ok }.thenByDescending { it.bitrate },
            )
            StreamOrder.Reliable -> listOf(base) + others.sortedWith(
                compareByDescending<Station> { it.ok }.thenByDescending { it.clicks },
            )
        }
        val streams = (directory.flatMap { it.streams } + tuneInStreams + s.streams).distinct().take(MAX_STREAMS)

        base.copy(
            streams = streams,
            tuneInId = tuneInId,
            favicon = base.favicon.ifEmpty { s.favicon },
            subtitle = base.subtitle.ifEmpty { s.subtitle },
        )
    }

    /** Favorites, recently played and settings as one JSON object. */
    fun exportJson(settings: android.content.SharedPreferences): String = org.json.JSONObject().apply {
        put("favorites", _favorites.value.toJsonArray())
        put("recent", _recent.value.toJsonArray())
        put("settings", org.json.JSONObject(settings.all.filterValues { it is String || it is Boolean || it is Int }))
    }.toString()

    /** Favorites and recently played from [exportJson]; settings are applied earlier, see RadioApp. */
    fun importStations(json: String) {
        val o = org.json.JSONObject(json)
        o.optJSONArray("favorites")?.toStations()?.let(::saveFavorites)
        o.optJSONArray("recent")?.toStations()?.let {
            _recent.value = it
            save(recentFile, it)
        }
    }

    fun clearCache() {
        memory.clear()
        http.clear()
    }

    private fun load(file: File): List<Station> =
        runCatching { JSONArray(file.readText()).toStations() }.getOrDefault(emptyList())

    private fun save(file: File, list: List<Station>) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(list.toJsonArray().toString())
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        private const val CACHE_MS = 10 * 60 * 1000L
        private const val MAX_STREAMS = 10

        /** Words that don't change which station it is ("ORF Hitradio Ö3 HQ" = "Hitradio Ö3"). */
        private val GENERIC = setOf(
            "radio", "hitradio", "orf", "live", "stream", "hd", "hq", "lq", "fm", "official", "offiziell",
            "mp3", "aac", "128", "192", "256", "320", "kbps", "high", "low", "quality", "web", "webradio", "the",
        )

        fun sameStation(a: String, b: String): Boolean {
            val ta = Station.normalize(a).split(" ").toSet()
            val tb = Station.normalize(b).split(" ").toSet()
            if (ta == tb) return true
            val core = (ta - GENERIC)
            return core.isNotEmpty() && core == (tb - GENERIC)
        }
    }
}
