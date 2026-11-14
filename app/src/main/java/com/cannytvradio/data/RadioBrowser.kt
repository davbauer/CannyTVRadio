package com.cannytvradio.data

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URLEncoder

/**
 * radio-browser.info: the community station directory (about 50,000 stations).
 * It runs on several volunteer servers, so every request walks the server list
 * until one answers, and remembers the one that did.
 */
class RadioBrowser(private val http: Http, private val prefs: SharedPreferences) {

    private val known = listOf("de1", "de2", "fi1", "nl1", "at1").map { "$it.api.radio-browser.info" }

    @Volatile
    private var hosts: List<String> =
        (listOfNotNull(prefs.getString(KEY_HOST, null)) + known + "all.api.radio-browser.info").distinct()

    @Volatile
    private var discovered = false

    /** The server that answered last, for the settings screen. */
    @Volatile
    var activeHost: String? = null
        private set

    /** Fetches [path], falling back to the last stored copy when no server answers. */
    suspend fun fetch(path: String): Fetched<String> {
        discover()
        var error: Exception? = null
        for (host in hosts) {
            try {
                val body = http.get("https://$host$path")
                if (host != hosts.first()) {
                    hosts = listOf(host) + (hosts - host)
                    prefs.edit().putString(KEY_HOST, host).apply()
                }
                activeHost = host
                http.store(path, body)
                return Fetched(body, stale = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CannyTVRadio", "radio-browser $host$path failed: $e")
                error = e
            }
        }
        activeHost = null
        http.getCached(path)?.let { return Fetched(it, stale = true) }
        throw error ?: IOException("No radio-browser server reachable")
    }

    suspend fun stations(path: String): Fetched<List<Station>> {
        val r = fetch(path)
        return Fetched(withContext(Dispatchers.Default) { parseStations(r.value) }, r.stale)
    }

    fun top(limit: Int = 40) = "/json/stations/topclick/$limit?hidebroken=true"

    fun byCountry(code: String, order: String = "clickcount", limit: Int = 60) =
        "/json/stations/search?countrycode=${enc(code)}&order=$order&reverse=true&limit=$limit&hidebroken=true"

    fun byState(code: String, state: String, limit: Int = 100) =
        "/json/stations/search?countrycode=${enc(code)}&state=${enc(state)}&order=clickcount&reverse=true&limit=$limit&hidebroken=true"

    fun byTag(tag: String, code: String? = null, limit: Int = 40) =
        "/json/stations/search?tag=${enc(tag)}" + (code?.let { "&countrycode=${enc(it)}" } ?: "") +
            "&order=clickcount&reverse=true&limit=$limit&hidebroken=true"

    fun byName(name: String, limit: Int = 50) =
        "/json/stations/search?name=${enc(name)}&order=clickcount&reverse=true&limit=$limit&hidebroken=true"

    fun byUuids(ids: List<String>) = "/json/stations/byuuid?uuids=${enc(ids.joinToString(","))}"

    /** Countries with their ISO code and station count, biggest first. */
    suspend fun countries(): Fetched<List<Country>> {
        val r = fetch("/json/countries?order=stationcount&reverse=true&hidebroken=true")
        val a = JSONArray(r.value)
        val list = (0 until a.length()).map { a.getJSONObject(it) }
            .map { Country(it.optString("iso_3166_1"), it.optString("name"), it.optInt("stationcount")) }
            .filter { it.code.length == 2 && it.stations > 0 }
        return Fetched(list, r.stale)
    }

    /**
     * Regions of a country, biggest first. Germany, Austria and Switzerland are mapped to their
     * official regions (all spellings of one state become one entry); elsewhere duplicates by
     * spelling are merged, noise is dropped and single-station leftovers are hidden.
     */
    suspend fun states(countryName: String, countryCode: String): Fetched<List<Region>> {
        val r = fetch("/json/states/${enc(countryName)}/?order=stationcount&reverse=true&hidebroken=true")
        val a = JSONArray(r.value)
        val raw = (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("country").equals(countryName, ignoreCase = true) }
            .map { Region(it.optString("name").trim(), it.optInt("stationcount")) }
            .filter { it.name.isNotBlank() }
        val list = if (Regions.hasTable(countryCode)) {
            raw.mapNotNull { reg -> Regions.code(countryCode, reg.name)?.let { it to reg } }
                .groupBy({ it.first }, { it.second })
                .map { (code, same) ->
                    Region(code, same.sumOf { it.stations }, code, same.sortedByDescending { it.stations }.map { it.name }.take(6))
                }
        } else {
            raw.filter { Regions.label(countryCode, it.name, "en") != null }
                .groupBy { Station.normalize(it.name) }
                .map { (_, same) ->
                    Region(same.maxBy { it.stations }.name, same.sumOf { it.stations }, null, same.map { it.name }.take(4))
                }
                .filter { it.stations >= 2 }
        }
        return Fetched(list.sortedByDescending { it.stations }, r.stale)
    }

    /** Tells the directory a station was played; this keeps its popularity ranking honest. */
    suspend fun click(uuid: String) {
        runCatching { withContext(Dispatchers.IO) { fetch("/json/url/${enc(uuid)}") } }
    }

    private suspend fun discover() {
        if (discovered) return
        discovered = true
        val found = withContext(Dispatchers.IO) {
            runCatching {
                InetAddress.getAllByName("all.api.radio-browser.info")
                    .map { it.canonicalHostName }
                    .filter { it.endsWith(".api.radio-browser.info") }
            }.getOrDefault(emptyList())
        }
        if (found.isNotEmpty()) hosts = (hosts.take(1) + found + hosts).distinct()
    }

    companion object {
        private const val KEY_HOST = "rb_host"

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        fun parseStations(body: String): List<Station> {
            val a = JSONArray(body)
            return (0 until a.length()).mapNotNull { runCatching { parse(a.getJSONObject(it)) }.getOrNull() }
                .filter { it.streams.isNotEmpty() && it.name.isNotBlank() }
        }

        private fun parse(o: JSONObject) = Station(
            id = o.getString("stationuuid"),
            name = Station.cleanName(o.optString("name")),
            streams = listOf(o.optString("url_resolved"), o.optString("url"))
                .map { it.trim() }.filter { it.startsWith("http") }.distinct(),
            favicon = o.optString("favicon").trim().takeIf { it.startsWith("http") } ?: "",
            homepage = o.optString("homepage").trim(),
            country = o.optString("country"),
            countryCode = o.optString("countrycode"),
            state = o.optString("state").trim(),
            language = o.optString("language"),
            tags = o.optString("tags").split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(8),
            codec = o.optString("codec").takeIf { it != "UNKNOWN" } ?: "",
            bitrate = o.optInt("bitrate"),
            hls = o.optInt("hls") == 1,
            votes = o.optInt("votes"),
            clicks = o.optInt("clickcount"),
            ok = o.optInt("lastcheckok", 1) == 1,
        )
    }
}

data class Fetched<T>(val value: T, val stale: Boolean)

data class Country(val code: String, val name: String, val stations: Int)

/**
 * A region to filter by. [code] is the official code where we know it ("DE-BY"); [variants] are the
 * directory's spellings that all mean this region and are searched together.
 */
data class Region(val name: String, val stations: Int, val code: String? = null, val variants: List<String> = listOf(name)) {
    val key: String get() = code ?: name
}
