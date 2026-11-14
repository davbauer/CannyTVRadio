package com.cannytvradio.ui

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cannytvradio.BuildConfig
import com.cannytvradio.R
import com.cannytvradio.RadioApp
import com.cannytvradio.data.DimStyle
import com.cannytvradio.data.Prefs
import com.cannytvradio.data.StreamOrder
import com.cannytvradio.player.EqPreset

@Composable
fun SettingsScreen(app: RadioApp, actions: Actions) {
    val prefs by app.settings.prefs.collectAsState()
    val context = LocalContext.current
    val ui = LocalUi.current
    val res = context.resources
    var checks by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    fun update(change: (Prefs) -> Prefs) = app.settings.update(change)

    // Ask both sources when the screen opens, so the status line is current.
    LaunchedEffect(checks) {
        testing = true
        runCatching { app.repo.browser.fetch("/json/stats") }
        runCatching { app.repo.tuneIn.search("radio") }
        testing = false
    }

    val listState = rememberLazyListState()
    val bar = rememberScrollbar(listState)
    LazyColumn(
        Modifier.fillMaxSize().scrollbar { bar.progress() },
        state = listState,
        contentPadding = PaddingValues(start = EDGE, end = EDGE + 40.dp, top = 6.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Section(stringResource(R.string.s_section_screen)) }
        item {
            Group {
                Toggle(stringResource(R.string.s_dim_enabled), stringResource(R.string.s_dim_enabled_desc), prefs.dimEnabled) {
                    update { it.copy(dimEnabled = !it.dimEnabled) }
                }
                Choice(
                    stringResource(R.string.s_dim_after),
                    null,
                    prefs.dimAfter.seconds.toDuration(res),
                    onStep = { by -> update { it.copy(dimAfter = it.dimAfter.step(by)) } },
                )
                Choice(
                    stringResource(R.string.s_dim_style),
                    null,
                    stringResource(if (prefs.dimStyle == DimStyle.Clock) R.string.style_clock else R.string.style_black),
                    onStep = { by -> update { it.copy(dimStyle = it.dimStyle.step(by)) } },
                )
            }
        }

        item { Section(stringResource(R.string.s_section_sound)) }
        item {
            Group {
                Toggle(stringResource(R.string.s_normalize), stringResource(R.string.s_normalize_desc), prefs.normalize) {
                    update { it.copy(normalize = !it.normalize) }
                }
                Choice(
                    stringResource(R.string.s_eq),
                    stringResource(R.string.s_eq_desc),
                    stringResource(eqLabel(prefs.eq)),
                    onStep = { by -> update { it.copy(eq = it.eq.step(by)) } },
                    preview = { EqCurve(prefs.eq) },
                )
            }
        }

        item { Section(stringResource(R.string.s_section_play)) }
        item {
            Group {
                Toggle(stringResource(R.string.s_resume), null, prefs.resumeOnStart) {
                    update { it.copy(resumeOnStart = !it.resumeOnStart) }
                }
                Choice(
                    stringResource(R.string.s_order),
                    stringResource(R.string.s_order_desc),
                    stringResource(if (prefs.streamOrder == StreamOrder.Quality) R.string.order_quality else R.string.order_reliable),
                    onStep = { by -> update { it.copy(streamOrder = it.streamOrder.step(by)) } },
                )
                val country = countryName(prefs.home)
                Action(
                    stringResource(R.string.s_home),
                    stringResource(R.string.s_home_desc),
                    if (prefs.homeCountry.isEmpty()) stringResource(R.string.home_auto, country) else country,
                ) {
                    if (prefs.homeCountry.isNotEmpty()) update { it.copy(homeCountry = "") } else ui.tab = Tab.Regions
                }
            }
        }

        item { Section(stringResource(R.string.s_section_general)) }
        item {
            Group {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val lang = appLanguage(context)
                    Choice(
                        stringResource(R.string.s_language),
                        null,
                        stringResource(
                            when (lang) {
                                "de" -> R.string.lang_de
                                "en" -> R.string.lang_en
                                else -> R.string.lang_system
                            },
                        ),
                        onStep = { by ->
                            val i = (LANGUAGES.indexOf(lang).coerceAtLeast(0) + by).mod(LANGUAGES.size)
                            setAppLanguage(context, LANGUAGES[i])
                        },
                    )
                }
                Action(stringResource(R.string.s_keys), stringResource(R.string.s_keys_desc), "") {}
                val rb = app.repo.browser.activeHost
                val ti = app.repo.tuneIn.reachable
                Action(
                    stringResource(R.string.s_sources),
                    listOf(
                        stringResource(if (rb != null) R.string.source_rb_ok else R.string.source_rb_down),
                        stringResource(
                            when (ti) {
                                true -> R.string.source_ti_ok
                                false -> R.string.source_ti_down
                                null -> R.string.source_ti_unknown
                            },
                        ),
                    ).joinToString("\n"),
                    stringResource(if (testing) R.string.checking else R.string.s_test),
                ) { if (!testing) checks++ }
                Action(stringResource(R.string.s_clear_cache), null, "") {
                    app.repo.clearCache()
                    actions.toast(context.getString(R.string.cache_cleared))
                }
            }
        }
        item {
            Text(
                stringResource(R.string.app_name) + " · " + stringResource(R.string.s_version, BuildConfig.VERSION_NAME),
                color = Glass.textFaint,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp, start = 6.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        color = Glass.textFaint,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 18.dp),
    )
}

/** Rows of one topic on one sheet of glass, like the settings on a phone. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            // Cached as one image, so scrolling the settings only moves it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .glass(RoundedCornerShape(26.dp), tint = 0.45f)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

@Composable
private fun SettingRow(
    title: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (focused: Boolean) -> Unit,
) {
    FocusCard(
        onClick = onClick,
        focusedScale = 1.015f,
        shape = RoundedCornerShape(20.dp),
        background = Color.Transparent,
        focusedBackground = Color.White.copy(alpha = 0.14f),
        modifier = modifier.fillMaxWidth(),
    ) { focused ->
        Row(
            Modifier.heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Glass.text, style = MaterialTheme.typography.titleMedium)
                if (description != null) Text(description, color = Glass.textDim, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(16.dp))
            trailing(focused)
        }
    }
}

@Composable
private fun Toggle(title: String, description: String?, on: Boolean, onToggle: () -> Unit) {
    SettingRow(title, description, onToggle) { Switch(on) }
}

/** A value with arrows: OK and Right go to the next value, Left to the previous one. */
@Composable
private fun Choice(
    title: String,
    description: String?,
    value: String,
    onStep: (Int) -> Unit,
    preview: (@Composable () -> Unit)? = null,
) {
    SettingRow(
        title,
        description,
        onClick = { onStep(1) },
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { onStep(-1); true }
                Key.DirectionRight -> { onStep(1); true }
                else -> false
            }
        },
    ) { focused ->
        if (preview != null) {
            preview()
            Spacer(Modifier.width(14.dp))
        }
        Arrow(Ico.ChevronLeft, focused)
        AnimatedContent(
            targetState = value,
            transitionSpec = { (fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 4 }) togetherWith fadeOut(tween(100)) },
            label = "value",
        ) { v ->
            Text(
                v,
                color = LocalAccent.current,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        Arrow(Ico.ChevronRight, focused)
    }
}

@Composable
private fun Arrow(icon: ImageVector, visible: Boolean) {
    Icon(icon, null, tint = if (visible) Glass.textDim else Color.Transparent, modifier = Modifier.size(18.dp))
}

@Composable
private fun Action(title: String, description: String?, value: String, onClick: () -> Unit) {
    SettingRow(title, description, onClick) {
        if (value.isNotEmpty()) Text(value, color = LocalAccent.current, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** The preset's five bands as small bars around a center line. */
@Composable
private fun EqCurve(preset: EqPreset) {
    val accent = LocalAccent.current
    Canvas(Modifier.size(width = 58.dp, height = 30.dp)) {
        val w = 6.dp.toPx()
        val gap = (size.width - 5 * w) / 4
        val mid = size.height / 2
        drawRect(Color.White.copy(alpha = 0.15f), Offset(0f, mid - 0.5f), Size(size.width, 1f))
        preset.gains.forEachIndexed { i, g ->
            val h = (kotlin.math.abs(g) / 10f * mid).coerceAtLeast(2f)
            val top = if (g >= 0) mid - h else mid
            drawRoundRect(accent, Offset(i * (w + gap), top), Size(w, h), CornerRadius(w / 2))
        }
    }
}

private fun eqLabel(p: EqPreset) = when (p) {
    EqPreset.Flat -> R.string.eq_flat
    EqPreset.Bass -> R.string.eq_bass
    EqPreset.Club -> R.string.eq_club
    EqPreset.Warm -> R.string.eq_warm
    EqPreset.Bright -> R.string.eq_bright
    EqPreset.Loudness -> R.string.eq_loudness
    EqPreset.Voice -> R.string.eq_voice
    EqPreset.Pop -> R.string.eq_pop
    EqPreset.Rock -> R.string.eq_rock
    EqPreset.Jazz -> R.string.eq_jazz
    EqPreset.Classical -> R.string.eq_classical
    EqPreset.Night -> R.string.eq_night
}

private inline fun <reified E : Enum<E>> E.step(by: Int): E = enumValues<E>().let { it[(ordinal + by).mod(it.size)] }

/** "" follows the TV, "de" and "en" pin the app's language. */
private val LANGUAGES = listOf("", "de", "en")

private fun appLanguage(context: Context): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().take(2)
    } else ""

private fun setAppLanguage(context: Context, tag: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    context.getSystemService(LocaleManager::class.java).applicationLocales =
        if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
}
