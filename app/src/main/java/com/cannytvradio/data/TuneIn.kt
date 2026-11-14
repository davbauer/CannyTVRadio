package com.cannytvradio.data

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URLEncoder

/**
 * TuneIn's public OPML directory. It is the second source: used for search when
 * radio-browser is down, and for extra backup streams of the same station.
 */
class TuneIn(private val http: Http) {

    @Volatile
    var reachable: Boolean? = null
        private set

    suspend fun search(query: String): List<Station> {
        val url = "$BASE/Search.ashx?query=${URLEncoder.encode(query, "UTF-8")}&types=station&render=json"
        val body = try {
            http.get(url).also { http.store(url, it); reachable = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reachable = false
            http.getCached(url) ?: return emptyList()
        }
        val items = JSONObject(body).optJSONArray("body") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("type") == "audio" && it.optString("item") == "station" }
            .map {
                val guide = it.optString("guide_id")
                Station(
                    id = "tunein:$guide",
                    name = Station.cleanName(it.optString("text")),
                    streams = emptyList(),
                    favicon = it.optString("image"),
                    bitrate = it.optString("bitrate").toIntOrNull() ?: 0,
                    codec = it.optString("formats").uppercase(),
                    source = Station.Source.TuneIn,
                    tuneInId = guide,
                    subtitle = it.optString("subtext").trim(),
                )
            }
            .filter { it.tuneInId.isNotBlank() && it.name.isNotBlank() }
    }

    /** Direct stream URLs for a TuneIn station, most reliable first. */
    suspend fun streams(guideId: String): List<String> {
        val url = "$BASE/Tune.ashx?id=${URLEncoder.encode(guideId, "UTF-8")}&render=json&formats=mp3,aac,ogg,hls"
        val body = try {
            http.get(url).also { http.store(url, it); reachable = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reachable = false
            http.getCached(url) ?: return emptyList()
        }
        val items = JSONObject(body).optJSONArray("body") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("element") == "audio" }
            .sortedWith(compareByDescending<JSONObject> { it.optBoolean("is_direct") }.thenByDescending { it.optInt("reliability") })
            .map { it.optString("url").trim() }
            // TuneIn answers with a placeholder clip when a stream isn't available in a region.
            .filter { it.startsWith("http") && "tunein.com/service" !in it && "notcompatible" !in it }
            .distinct()
    }

    private companion object {
        const val BASE = "https://opml.radiotime.com"
    }
}
