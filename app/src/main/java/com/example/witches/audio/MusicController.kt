package com.example.witches.audio

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.example.witches.R
import java.io.IOException

/** Foreground-only music. The Compose owner must call close when its lifecycle ends. */
@MainThread
class MusicController(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val policy = MusicPolicy()
    private val positions = mutableMapOf<MusicTrack, Int>()
    private var closed = false
    private var player: MediaPlayer? = null
    private var playerTrack: MusicTrack? = null
    private var prepared = false
    private var currentVolume = 0f
    private var fade: ValueAnimator? = null
    private var fadeTarget: Float? = null
    private var switching = false
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            if (!closed) apply(
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> policy.focusGained()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> policy.focusLost(temporary = true)
                    else -> policy.focusLost(temporary = false)
                },
            )
        }, Handler(Looper.getMainLooper()))
        .build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && !closed) {
                apply(policy.interrupt())
            }
        }
    }
    private val receiverRegistered = try {
        ContextCompat.registerReceiver(this.context, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        true
    } catch (_: RuntimeException) {
        false
    }

    fun update(track: MusicTrack, enabled: Boolean, volume: Float = .35f, active: Boolean) {
        if (!closed) apply(policy.update(track, enabled, volume, active))
    }

    private fun apply(decision: MusicDecision) {
        if (closed) return
        if (decision.abandonFocus) abandonFocus()
        if (decision.requestFocus) {
            val granted = try {
                audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } catch (_: RuntimeException) {
                false
            }
            apply(policy.focusResult(granted))
            return
        }
        if (!decision.play) {
            cancelFade()
            releasePlayer()
            return
        }
        val wanted = policy.request
        if (playerTrack == wanted.track && player != null) {
            if (prepared) {
                if (switching) cancelFade()
                if (fadeTarget != wanted.volume && currentVolume != wanted.volume) fadeTo(wanted.volume)
            }
            return
        }
        if (player != null && prepared) {
            if (!switching) {
                switching = true
                fadeTo(0f) {
                    switching = false
                    releasePlayer()
                    if (policy.decision().play) prepare(policy.request.track)
                }
            }
        } else {
            releasePlayer()
            prepare(wanted.track)
        }
    }

    private fun prepare(track: MusicTrack) {
        val next = try {
            MediaPlayer()
        } catch (_: RuntimeException) {
            playbackFailed()
            return
        }
        player = next
        playerTrack = track
        prepared = false
        currentVolume = 0f
        try {
            next.setAudioAttributes(attributes)
            next.setVolume(0f, 0f)
            val resource = if (track == MusicTrack.MENU) R.raw.menu_music else R.raw.game_music
            context.resources.openRawResourceFd(resource).use { asset ->
                next.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
            }
            next.setOnPreparedListener {
                if (player === next && !closed) {
                    prepared = true
                    if (!policy.decision().play || policy.request.track != track) {
                        apply(policy.decision())
                    } else try {
                        next.isLooping = true
                        positions[track]?.let { next.seekTo(it.toLong(), MediaPlayer.SEEK_CLOSEST) }
                        next.start()
                        fadeTo(policy.request.volume)
                    } catch (_: RuntimeException) {
                        playbackFailed()
                    }
                }
            }
            next.setOnErrorListener { _, _, _ ->
                if (player === next) playbackFailed()
                true
            }
            next.prepareAsync()
        } catch (_: IOException) {
            playbackFailed()
        } catch (_: RuntimeException) {
            playbackFailed()
        }
    }

    private fun playbackFailed() {
        // Audio errors stop music for this activation; gameplay remains available.
        apply(policy.interrupt())
    }

    private fun fadeTo(target: Float, after: () -> Unit = {}) {
        cancelFade(keepSwitch = true)
        val animation = ValueAnimator.ofFloat(currentVolume, target).apply {
            duration = 400
            addUpdateListener {
                currentVolume = it.animatedValue as Float
                try {
                    player?.setVolume(currentVolume, currentVolume)
                } catch (_: RuntimeException) {
                    playbackFailed()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fade = null
                    fadeTarget = null
                    after()
                }
            })
        }
        fade = animation
        fadeTarget = target
        animation.start()
    }

    private fun cancelFade(keepSwitch: Boolean = false) {
        fade?.removeAllListeners()
        fade?.removeAllUpdateListeners()
        fade?.cancel()
        fade = null
        fadeTarget = null
        if (!keepSwitch) switching = false
    }

    private fun releasePlayer() {
        val previous = player
        if (previous != null && prepared) {
            try {
                playerTrack?.let { positions[it] = previous.currentPosition }
            } catch (_: RuntimeException) {
                // An errored player may no longer expose a position.
            }
        }
        player = null
        playerTrack = null
        prepared = false
        currentVolume = 0f
        previous?.release()
    }

    private fun abandonFocus() {
        try {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        } catch (_: RuntimeException) {
            // Nothing is playing even when the audio service has gone away.
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelFade()
        releasePlayer()
        abandonFocus()
        if (receiverRegistered) context.unregisterReceiver(noisyReceiver)
    }
}
