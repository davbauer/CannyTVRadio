package com.cannytvradio

import android.content.ComponentName
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cannytvradio.player.PlaybackService
import com.cannytvradio.ui.RadioRoot
import com.cannytvradio.data.primaryLocale
import com.cannytvradio.ui.Motion
import com.cannytvradio.ui.RadioTheme
import com.cannytvradio.ui.Tab
import com.cannytvradio.ui.Ui
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private val ui = Ui()
    private var controller: ListenableFuture<MediaController>? = null
    private var swallowUp = false
    private var lastStep = 0L

    private val app get() = application as RadioApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Connecting a controller starts the playback service, which keeps the radio on in the background.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()

        if (savedInstanceState == null && app.settings.prefs.value.resumeOnStart && app.player.state.value.station == null) {
            app.repo.recent.value.firstOrNull()?.let { app.player.play(it) }
        }
        if (app.repo.favorites.value.isNotEmpty()) {
            ui.tab = Tab.Favorites
            ui.startTab = Tab.Favorites
        }
        app.writeExport()
        useFastestDisplayMode()
        setContent { RadioTheme { RadioRoot(app, ui) } }
        // The app paints its own full-screen background; skip the window's, so each frame fills the screen once.
        window.setBackgroundDrawable(null)
    }

    /** Picks the highest refresh rate the TV offers at its current resolution (60, 120, 144 Hz). */
    private fun useFastestDisplayMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.modeId != current.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    /** A language switch is applied in place: no restart, the current screen and focus stay. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        java.util.Locale.setDefault(newConfig.primaryLocale())
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val volume = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
        if (volume) return super.dispatchKeyEvent(event)

        val now = SystemClock.uptimeMillis()
        ui.lastInput = now
        if (event.action == KeyEvent.ACTION_DOWN && code in DPAD) {
            // A held arrow repeats about 20 times a second, faster than this TV can draw.
            // Take one step every 110 ms and drop the rest, so focus never queues up behind the screen.
            if (event.repeatCount > 0 && now - lastStep < 110) return true
            lastStep = now
            Motion.keyPressed(app.scope, repeat = event.repeatCount > 0)
        }

        // The first key on the dark screen only wakes it up.
        if (event.action == KeyEvent.ACTION_DOWN && ui.dimmed) {
            ui.dimmed = false
            ui.darkNow = false
            swallowUp = true
            return true
        }
        if (swallowUp) {
            if (event.action == KeyEvent.ACTION_UP) swallowUp = false
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val player = app.player
            when (code) {
                in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> if (!ui.typing) {
                    player.preset(code - KeyEvent.KEYCODE_0)
                    return true
                }
                KeyEvent.KEYCODE_0 -> if (!ui.typing) {
                    player.preset(10)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    player.skip(1)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    player.skip(-1)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    player.toggle()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player.resume()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> {
                    player.pause()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        val DPAD = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    override fun onResume() {
        super.onResume()
        app.updater.resumeAfterPermission(this)
        app.updater.check()
    }

    override fun onStop() {
        super.onStop()
        app.writeExport()
    }

    override fun onDestroy() {
        controller?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
