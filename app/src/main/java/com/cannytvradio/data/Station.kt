package com.cannytvradio.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * One station, merged from every source we know about. Never changed after creation, which lets
 * Compose skip tiles whose station is the same.
 * [streams] is ordered: the URL to try first, then the fallbacks.
 */
@androidx.compose.runtime.Immutable
data class Station(
    val id: String,
    val name: String,
    val streams: List<String>,
    val favicon: String = "",
    val homepage: String = "",
    val country: String = "",
    val countryCode: String = "",
    val state: String = "",
    val language: String = "",
    val tags: List<String> = emptyList(),
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Boolean = false,
    val votes: Int = 0,
    val clicks: Int = 0,
    val ok: Boolean = true,
    val source: Source = Source.RadioBrowser,
    /** TuneIn guide id ("s8007"), kept so its streams can be looked up again later. */
    val tuneInId: String = "",
    /** Short description or current show, when a source provides one. */
    val subtitle: String = "",
) {
    enum class Source { RadioBrowser, TuneIn }

    val key: String get() = normalize(name)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("streams", JSONArray(streams))
        put("favicon", favicon); put("homepage", homepage); put("country", country)
        put("countryCode", countryCode); put("state", state); put("language", language)
        put("tags", JSONArray(tags)); put("codec", codec); put("bitrate", bitrate)
        put("hls", hls); put("votes", votes); put("clicks", clicks); put("ok", ok)
        put("source", source.name); put("tuneInId", tuneInId); put("subtitle", subtitle)
    }

    companion object {
        fun fromJson(o: JSONObject) = Station(
            id = o.getString("id"),
            name = cleanName(o.optString("name")),
            streams = o.optJSONArray("streams").strings(),
            favicon = o.optString("favicon"),
            homepage = o.optString("homepage"),
            country = o.optString("country"),
            countryCode = o.optString("countryCode"),
            state = o.optString("state"),
            language = o.optString("language"),
            tags = o.optJSONArray("tags").strings(),
            codec = o.optString("codec"),
            bitrate = o.optInt("bitrate"),
            hls = o.optBoolean("hls"),
            votes = o.optInt("votes"),
            clicks = o.optInt("clicks"),
            ok = o.optBoolean("ok", true),
            source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(Source.RadioBrowser),
            tuneInId = o.optString("tuneInId"),
            subtitle = o.optString("subtitle"),
        )

        private val TECH = Regex(
            "\\b(\\d{2,3}\\s?(k|kb|kbps|kbit|kbit/s)|aac\\+?|he-aac|mp3|ogg|opus|flac|hq|lq|hls)\\b",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Name without the technical extras the directory often carries:
         * "FM4 | ORF | HQ" -> "FM4", "Radio Paradise Main Mix (EU) 320k AAC" -> "Radio Paradise Main Mix (EU)".
         */
        fun cleanName(raw: String): String {
            val first = raw.split(" | ", " || ").first().trim()
            val cleaned = first.replace(TECH, " ")
                .replace(Regex("\\(\\s*\\)|\\[\\s*]"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim(' ', '-', '|', '/', ',', '_', '#', '*', '~')
            return cleaned.ifBlank { raw.trim() }
        }

        /** Lowercase, umlauts spelled out, accents and punctuation removed: "Ö3 Wien!" -> "oe3 wien". */
        fun normalize(s: String): String {
            val spelled = s.lowercase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            return Normalizer.normalize(spelled, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        }
    }
}

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

fun List<Station>.toJsonArray() = JSONArray().also { a -> forEach { a.put(it.toJson()) } }

fun JSONArray.toStations(): List<Station> =
    (0 until length()).mapNotNull { runCatching { Station.fromJson(getJSONObject(it)) }.getOrNull() }
