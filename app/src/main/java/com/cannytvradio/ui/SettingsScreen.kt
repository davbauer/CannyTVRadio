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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
import com.cannytvradio.data.UpdateError
import com.cannytvradio.data.UpdateState
import com.cannytvradio.data.StreamOrder
import com.cannytvradio.player.EQ_BANDS
import com.cannytvradio.player.Eq
import com.cannytvradio.player.EqPreset

@Composable
fun SettingsScreen(app: RadioApp, actions: Actions) {
    val prefs by app.settings.prefs.collectAsState()
    val updateState by app.updater.state.collectAsState()
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
        val update = updateState
        update.available?.let { info ->
            item("update-title") { Section(stringResource(R.string.s_section_update)) }
            item("update") {
                Group {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.update_available, info.versionName), color = Glass.text, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.update_versions, BuildConfig.VERSION_NAME, info.versionName),
                            color = Glass.textFaint,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        info.changes.take(6).forEach { change ->
                            Text("•  $change", color = Glass.textDim, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    val status = updateStepText(update)
                    Action(stringResource(R.string.update_install), status, "") {
                        (context as? android.app.Activity)?.let(app.updater::install)
                    }
                }
            }
        }

        item("screen-title") { Section(stringResource(R.string.s_section_screen)) }
        item("screen") {
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
                    stringResource(
                        when (prefs.dimStyle) {
                            DimStyle.Time -> R.string.style_time
                            DimStyle.TimeAndInfo -> R.string.style_time_info
                            DimStyle.Nothing -> R.string.style_nothing
                        },
                    ),
                    onStep = { by -> update { it.copy(dimStyle = it.dimStyle.step(by)) } },
                )
            }
        }

        item("sound-title") { Section(stringResource(R.string.s_section_sound)) }
        item("sound") {
            Group {
                Toggle(stringResource(R.string.s_normalize), stringResource(R.string.s_normalize_desc), prefs.normalize) {
                    update { it.copy(normalize = !it.normalize) }
                }
                val gains = Eq.gains(prefs.eq, prefs.customs)
                Choice(
                    stringResource(R.string.s_eq),
                    stringResource(R.string.s_eq_desc),
                    eqLabel(prefs.eq),
                    onStep = { by -> update { it.copy(eq = Eq.ALL[(Eq.ALL.indexOf(it.eq) + by).mod(Eq.ALL.size)]) } },
                    preview = { EqCurve(gains) },
                )
                val custom = Eq.CUSTOM.indexOf(prefs.eq)
                if (custom >= 0) {
                    BandEditor(prefs.customs[custom]) { band, value ->
                        update { p ->
                            p.copy(customs = p.customs.mapIndexed { i, bands -> if (i == custom) bands.mapIndexed { b, v -> if (b == band) value else v } else bands })
                        }
                    }
                    Action(stringResource(R.string.s_eq_reset), null, "") {
                        update { p -> p.copy(customs = p.customs.mapIndexed { i, bands -> if (i == custom) List(5) { 0 } else bands }) }
                    }
                }
            }
        }

        item("play-title") { Section(stringResource(R.string.s_section_play)) }
        item("play") {
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

        item("general-title") { Section(stringResource(R.string.s_section_general)) }
        item("general") {
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
                Action(
                    stringResource(R.string.s_updates),
                    // Always the installed version first, then what the last check found.
                    listOfNotNull(
                        updateState.available?.let { stringResource(R.string.update_versions, BuildConfig.VERSION_NAME, it.versionName) }
                            ?: stringResource(R.string.update_installed, BuildConfig.VERSION_NAME),
                        when {
                            updateState.checking -> stringResource(R.string.checking)
                            updateState.available != null -> null
                            updateState.lastCheckOk == true -> stringResource(R.string.update_current)
                            updateState.lastCheckOk == false -> stringResource(R.string.update_check_failed) + " " + updateErrorText(updateState.error)
                            else -> null
                        },
                    ).joinToString(" · ") + (updateStepText(updateState)?.let { "\n$it" } ?: ""),
                    stringResource(if (updateState.available != null) R.string.s_install_now else R.string.s_check_updates),
                ) {
                    // Found something: install right here, no need to go looking for it at the top.
                    if (updateState.available != null) (context as? android.app.Activity)?.let(app.updater::install)
                    else app.updater.check(force = true)
                }
                Action(stringResource(R.string.s_reload_lists), stringResource(R.string.s_reload_lists_desc), "") {
                    app.browse.reloadAll()
                    actions.toast(context.getString(R.string.lists_reloading))
                }
            }
        }
        item("version") {
            Text(
                stringResource(R.string.app_name) + " · " + stringResource(R.string.s_version, BuildConfig.VERSION_NAME),
                color = Glass.textFaint,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp, start = 6.dp),
            )
        }
    }
}

/** Where an update install stands: progress, the next step for the user, or what went wrong. */
@Composable
private fun updateStepText(update: UpdateState): String? = when (update.step) {
    UpdateState.Step.Downloading -> stringResource(R.string.update_downloading, (update.progress * 100).toInt())
    UpdateState.Step.Verifying -> stringResource(R.string.update_verifying)
    UpdateState.Step.NeedsPermission -> stringResource(R.string.update_allow)
    UpdateState.Step.Installing -> stringResource(R.string.update_installing)
    UpdateState.Step.Failed -> updateErrorText(update.error)
    UpdateState.Step.Idle -> null
}

/** The exact reason an update check or install failed, with technical detail where useful. */
@Composable
private fun updateErrorText(error: UpdateError?): String {
    if (error == null) return stringResource(R.string.update_err_other, "?")
    return when (error.kind) {
        UpdateError.Kind.Offline -> stringResource(R.string.update_err_offline)
        UpdateError.Kind.Http -> stringResource(R.string.update_err_http, error.detail)
        UpdateError.Kind.Checksum -> stringResource(R.string.update_err_checksum)
        UpdateError.Kind.Size -> stringResource(R.string.update_err_size, error.detail)
        UpdateError.Kind.Signature -> stringResource(R.string.update_err_signature)
        UpdateError.Kind.NoInstaller -> stringResource(R.string.update_err_installer, error.detail)
        UpdateError.Kind.Other -> stringResource(R.string.update_err_other, error.detail)
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
private fun EqCurve(gains: FloatArray) {
    val accent = LocalAccent.current
    Canvas(Modifier.size(width = 58.dp, height = 30.dp)) {
        val w = 6.dp.toPx()
        val gap = (size.width - 5 * w) / 4
        val mid = size.height / 2
        drawRect(Color.White.copy(alpha = 0.15f), Offset(0f, mid - 0.5f), Size(size.width, 1f))
        gains.forEachIndexed { i, g ->
            val h = (kotlin.math.abs(g) / Eq.RANGE * mid).coerceAtLeast(2f)
            val top = if (g >= 0) mid - h else mid
            drawRoundRect(accent, Offset(i * (w + gap), top), Size(w, h), CornerRadius(w / 2))
        }
    }
}

@Composable
private fun eqLabel(id: String): String {
    val custom = Eq.CUSTOM.indexOf(id)
    if (custom >= 0) return stringResource(R.string.eq_custom, custom + 1)
    return stringResource(
        when (EqPreset.valueOf(id)) {
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
        },
    )
}

/**
 * Five vertical sliders for a custom profile. Same pattern as moving favorites:
 * OK on a band picks it up, up and down change it by 1 dB (you hear it right away), OK lets go.
 */
@Composable
private fun BandEditor(bands: List<Int>, onChange: (band: Int, value: Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
        Text(stringResource(R.string.s_eq_edit_desc), color = Glass.textDim, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EQ_BANDS.forEachIndexed { i, label ->
                BandSlider(label, bands[i], { by -> onChange(i, (bands[i] + by).coerceIn(-Eq.RANGE, Eq.RANGE)) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BandSlider(label: String, value: Int, onStep: (Int) -> Unit, modifier: Modifier) {
    var grabbed by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    FocusCard(
        onClick = { grabbed = !grabbed },
        onFocusChange = { if (!it) grabbed = false },
        focusedScale = 1.04f,
        shape = RoundedCornerShape(18.dp),
        background = Color.White.copy(alpha = 0.05f),
        focusedBackground = Color.White.copy(alpha = if (grabbed) 0.22f else 0.13f),
        modifier = modifier.height(168.dp).onPreviewKeyEvent { e ->
            if (!grabbed || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionUp -> { onStep(1); true }
                Key.DirectionDown -> { onStep(-1); true }
                Key.Back -> { grabbed = false; true }
                else -> false
            }
        },
    ) { focused ->
        Column(Modifier.fillMaxSize().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                (if (value > 0) "+" else "") + "$value dB",
                color = if (focused) Color.White else Glass.textDim,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Icon(Ico.ChevronUp, null, tint = if (grabbed) Color.White else Color.Transparent, modifier = Modifier.size(16.dp))
            Canvas(Modifier.weight(1f).width(26.dp)) {
                val x = size.width / 2
                val track = 4.dp.toPx()
                val mid = size.height / 2
                val y = mid - (value.toFloat() / Eq.RANGE) * (mid - 8.dp.toPx())
                drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(x - track / 2, 0f), Size(track, size.height), CornerRadius(track / 2))
                drawRoundRect(accent, Offset(x - track / 2, minOf(y, mid)), Size(track, kotlin.math.abs(mid - y)), CornerRadius(track / 2))
                drawCircle(Color.White, if (grabbed) 9.dp.toPx() else 7.dp.toPx(), Offset(x, y))
            }
            Icon(Ico.ChevronDown, null, tint = if (grabbed) Color.White else Color.Transparent, modifier = Modifier.size(16.dp))
            Text(label, color = Glass.textFaint, style = MaterialTheme.typography.bodyMedium)
        }
    }
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
