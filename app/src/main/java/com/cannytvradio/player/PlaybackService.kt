package com.cannytvradio.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cannytvradio.MainActivity
import com.cannytvradio.RadioApp

/**
 * Keeps the radio playing when you leave the app, and lets the remote's media keys,
 * Google TV and the system control it. Next/previous switch between favorites.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val controller = (application as RadioApp).player
        val player = object : ForwardingPlayer(controller.player) {
            private val extra = setOf(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )

            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().addAll(*extra.toIntArray()).build()

            override fun isCommandAvailable(command: Int) = command in extra || super.isCommandAvailable(command)
            override fun play() = controller.resume()
            override fun seekToNext() = controller.skip(1)
            override fun seekToNextMediaItem() = controller.skip(1)
            override fun seekToPrevious() = controller.skip(-1)
            override fun seekToPreviousMediaItem() = controller.skip(-1)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        // The player belongs to the app, so only the session is released here.
        session?.release()
        session = null
        super.onDestroy()
    }
}
