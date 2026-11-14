package com.cannytvradio.data

import android.content.SharedPreferences
import android.content.res.Resources
import com.cannytvradio.player.EqPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DimAfter(val seconds: Int) { S10(10), S30(30), M1(60), M5(300) }

/** What the dark screen shows in front. */
enum class DimStyle { Time, TimeAndInfo, AnalogClock, Station, Nothing }

/** Moving background behind it; none by default. */
enum class SaverVisual { None, Aurora, Starfield }

/** How visible the screensaver is: scales its content and the TV backlight. */
enum class SaverBrightness(val level: Float, val backlight: Float) { Dim(0.6f, 0.01f), Normal(1f, 0.06f), Bright(1.6f, 0.3f) }

/** How strongly the visuals move with the music. */
enum class SaverReact(val amount: Float) { Off(0f), Subtle(0.45f), Medium(0.9f), Strong(1.5f) }

enum class StreamOrder { Quality, Reliable }

data class Prefs(
    /** Screen goes dark by itself after [dimAfter] without input; the moon in the bar toggles it. */
    val dimEnabled: Boolean = true,
    val dimAfter: DimAfter = DimAfter.S10,
    val dimStyle: DimStyle = DimStyle.TimeAndInfo,
    val saverVisual: SaverVisual = SaverVisual.None,
    val saverBrightness: SaverBrightness = SaverBrightness.Normal,
    val saverReact: SaverReact = SaverReact.Off,
    /** Shifts the visuals against the music on this TV: positive shows the light later. Set with the sync helper. */
    val saverSyncMs: Int = 0,
    /** How late the beat detection is on this TV, measured by the sync helper and made up for. */
    val saverDetectMs: Int = 0,
    val resumeOnStart: Boolean = true,
    val streamOrder: StreamOrder = StreamOrder.Quality,
    /** Bring every station to the same loudness. */
    val normalize: Boolean = true,
    /** A preset name or "Custom1".."Custom3" ([com.cannytvradio.player.Eq]). */
    val eq: String = EqPreset.Flat.name,
    /** The three custom profiles, dB per band. */
    val customs: List<List<Int>> = List(3) { List(5) { 0 } },
    /** ISO country code; empty means "follow the TV". */
    val homeCountry: String = "",
) {
    val home: String
        get() = homeCountry.ifEmpty {
            Resources.getSystem().configuration.primaryLocale().country.ifEmpty { "DE" }
        }
}

class Settings(private val sp: SharedPreferences) {
    private val state = MutableStateFlow(load())
    val prefs: StateFlow<Prefs> = state.asStateFlow()

    fun update(change: (Prefs) -> Prefs) {
        val next = change(state.value)
        state.value = next
        sp.edit()
            .putBoolean("dimEnabled", next.dimEnabled)
            .putString("dimAfter", next.dimAfter.name)
            .putString("dimStyle", next.dimStyle.name)
            .putString("saverVisual", next.saverVisual.name)
            .putString("saverBrightness", next.saverBrightness.name)
            .putString("saverReact", next.saverReact.name)
            .putInt("saverSyncMs", next.saverSyncMs)
            .putInt("saverDetectMs", next.saverDetectMs)
            .putBoolean("resumeOnStart", next.resumeOnStart)
            .putString("streamOrder", next.streamOrder.name)
            .putBoolean("normalize", next.normalize)
            .putString("eq", next.eq)
            .putString("customs", next.customs.joinToString(";") { it.joinToString(",") })
            .putString("homeCountry", next.homeCountry)
            .apply()
    }

    private fun load() = Prefs(
        dimEnabled = sp.getBoolean("dimEnabled", sp.getString("dimAfter", null) != "Off"),
        dimAfter = enumOr(sp.getString("dimAfter", null), DimAfter.S10),
        // Older versions stored "Clock" and "Black".
        dimStyle = when (val v = sp.getString("dimStyle", null)) {
            "Clock" -> DimStyle.TimeAndInfo
            "Black" -> DimStyle.Nothing
            // The bouncing logo was removed.
            "Bounce" -> DimStyle.Station
            else -> enumOr(v, DimStyle.TimeAndInfo)
        },
        saverVisual = enumOr(sp.getString("saverVisual", null), SaverVisual.None),
        saverBrightness = enumOr(sp.getString("saverBrightness", null), SaverBrightness.Normal),
        saverReact = enumOr(sp.getString("saverReact", null), SaverReact.Off),
        saverSyncMs = sp.getInt("saverSyncMs", 0),
        saverDetectMs = sp.getInt("saverDetectMs", 0),
        resumeOnStart = sp.getBoolean("resumeOnStart", true),
        streamOrder = enumOr(sp.getString("streamOrder", null), StreamOrder.Quality),
        normalize = sp.getBoolean("normalize", true),
        eq = sp.getString("eq", null)?.takeIf { it in com.cannytvradio.player.Eq.ALL } ?: EqPreset.Flat.name,
        customs = parseCustoms(sp.getString("customs", null)),
        homeCountry = sp.getString("homeCountry", "") ?: "",
    )

    private fun parseCustoms(text: String?): List<List<Int>> {
        val parsed = text?.split(";")?.map { p -> p.split(",").mapNotNull { it.toIntOrNull() } }.orEmpty()
        return List(3) { i -> parsed.getOrNull(i)?.takeIf { it.size == 5 } ?: List(5) { 0 } }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}

/** The configuration's main language on every Android version (locales[0] needs Android 7). */
fun android.content.res.Configuration.primaryLocale(): java.util.Locale =
    androidx.core.os.ConfigurationCompat.getLocales(this)[0] ?: java.util.Locale.getDefault()
