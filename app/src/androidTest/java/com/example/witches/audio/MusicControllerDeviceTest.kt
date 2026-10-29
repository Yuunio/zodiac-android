package com.example.witches.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.witches.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises bundled WAV decoding on Android; it does not claim a listening or focus test. */
@RunWith(AndroidJUnit4::class)
class MusicControllerDeviceTest {
    @Test fun bothScoresPrepareLoopAndEnterPlayingState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (resource in listOf(R.raw.menu_music, R.raw.game_music)) {
            val holder = AtomicReference<MediaPlayer?>()
            val failure = AtomicReference<String?>()
            val ready = CountDownLatch(1)
            val duration = AtomicInteger()
            val playing = AtomicBoolean()
            val looping = AtomicBoolean()
            try {
                instrumentation.runOnMainSync {
                    try {
                        val player = MediaPlayer()
                        holder.set(player)
                        player.setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        player.setVolume(0f, 0f)
                        context.resources.openRawResourceFd(resource).use { asset ->
                            player.setDataSource(asset.fileDescriptor, asset.startOffset, asset.length)
                        }
                        player.setOnPreparedListener {
                            duration.set(it.duration)
                            ready.countDown()
                        }
                        player.setOnErrorListener { _, what, extra ->
                            failure.set("MediaPlayer error $what/$extra for resource $resource")
                            ready.countDown()
                            true
                        }
                        player.prepareAsync()
                    } catch (error: Exception) {
                        failure.set(error.toString())
                        ready.countDown()
                    }
                }
                assertTrue("WAV preparation timed out", ready.await(10, TimeUnit.SECONDS))
                assertNull(failure.get())
                assertTrue("Expected a complete 80–100 second composition", duration.get() in 80_000..100_000)
                instrumentation.runOnMainSync {
                    try {
                        requireNotNull(holder.get()).let { player ->
                            player.isLooping = true
                            player.start()
                            playing.set(player.isPlaying)
                            looping.set(player.isLooping)
                            player.pause()
                        }
                    } catch (error: Exception) {
                        failure.set(error.toString())
                    }
                }
                assertNull(failure.get())
                assertTrue("Prepared WAV must enter Started state", playing.get())
                assertTrue("Looping must be enabled", looping.get())
            } finally {
                instrumentation.runOnMainSync { holder.getAndSet(null)?.release() }
            }
        }
    }
}
