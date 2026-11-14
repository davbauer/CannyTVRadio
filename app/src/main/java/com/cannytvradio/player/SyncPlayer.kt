package com.cannytvradio.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** The built-in click track: a knock every [SYNC_BEAT_MS], the first one at [SYNC_FIRST_BEAT_MS]. */
const val SYNC_FIRST_BEAT_MS = 1000L
const val SYNC_BEAT_MS = 500L

/**
 * A small player for the sync helper. It plays the built-in click track through the same kind of
 * audio path as the radio (same output, same sound processing and beat detection in [sound]),
 * so the delay it shows is the delay the screensaver has. It loops until released.
 */
@OptIn(UnstableApi::class)
fun syncPlayer(context: Context, sound: SoundProcessor): ExoPlayer {
    val renderers = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
            DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(sound)).build()
    }
    val player = ExoPlayer.Builder(context, renderers)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build()
    player.addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            // Start over from the beginning, so positions and beat times stay in step.
            if (state == Player.STATE_ENDED) {
                player.seekTo(0)
                player.play()
            }
        }
    })
    player.setMediaItem(MediaItem.fromUri("asset:///sync.mp3"))
    player.prepare()
    player.play()
    return player
}
