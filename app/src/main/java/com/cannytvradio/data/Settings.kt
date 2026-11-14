package com.cannytvradio.data

import android.content.SharedPreferences
import android.content.res.Resources
import com.cannytvradio.player.EqPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DimAfter(val seconds: Int) { S10(10), S30(30), M1(60), M5(300) }

enum class DimStyle { Clock, Black }

enum class StreamOrder { Quality, Reliable }

data class Prefs(
    /** Screen goes dark by itself after [dimAfter] without input; the moon in the bar toggles it. */
    val dimEnabled: Boolean = true,
    val dimAfter: DimAfter = DimAfter.S10,
    val dimStyle: DimStyle = DimStyle.Clock,
    val resumeOnStart: Boolean = true,
    val streamOrder: StreamOrder = StreamOrder.Quality,
    /** Bring every station to the same loudness. */
    val normalize: Boolean = true,
    val eq: EqPreset = EqPreset.Flat,
    /** ISO country code; empty means "follow the TV". */
    val homeCountry: String = "",
) {
    val home: String
        get() = homeCountry.ifEmpty {
            Resources.getSystem().configuration.locales[0].country.ifEmpty { "DE" }
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
            .putBoolean("resumeOnStart", next.resumeOnStart)
            .putString("streamOrder", next.streamOrder.name)
            .putBoolean("normalize", next.normalize)
            .putString("eq", next.eq.name)
            .putString("homeCountry", next.homeCountry)
            .apply()
    }

    private fun load() = Prefs(
        dimEnabled = sp.getBoolean("dimEnabled", sp.getString("dimAfter", null) != "Off"),
        dimAfter = enumOr(sp.getString("dimAfter", null), DimAfter.S10),
        dimStyle = enumOr(sp.getString("dimStyle", null), DimStyle.Clock),
        resumeOnStart = sp.getBoolean("resumeOnStart", true),
        streamOrder = enumOr(sp.getString("streamOrder", null), StreamOrder.Quality),
        normalize = sp.getBoolean("normalize", true),
        eq = enumOr(sp.getString("eq", null), EqPreset.Flat),
        homeCountry = sp.getString("homeCountry", "") ?: "",
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
