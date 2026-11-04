package com.dineshdevkota.cinevault.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dineshdevkota.cinevault.MainActivity
import com.dineshdevkota.cinevault.smb.SmbService
import com.dineshdevkota.cinevault.smb.SourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Decodes the fixture's 12-second H.264 movie through the app's real SMB data source. */
@RunWith(AndroidJUnit4::class)
class SmbPlaybackTest {
    @Test
    fun playbackAndSeekStreamTheScannedMovie() = runBlocking {
        val host = InstrumentationRegistry.getArguments().getString("nasHost")
        assumeTrue("Pass nasHost to run SMB playback", !host.isNullOrBlank())
        val config = SourceConfig(UUID.randomUUID().toString(), host!!, 1445, "CINEVAULT", "Movies", "cinevault", "fixture-only")
        val smb = SmbService()
        try {
            val movie = smb.scanVideos(config).single()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var player: ExoPlayer? = null
                var view: PlayerView? = null
                val observation = PlaybackObservation()
                try {
                    scenario.onActivity { activity ->
                        val files = DataSource.Factory { SmbDataSource { smb.open(config, movie.path) } }
                        val sources = ProgressiveMediaSource.Factory(
                            files, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
                        ).setLoadErrorHandlingPolicy(SmbDataSource.loadErrorPolicy)
                        val exo = ExoPlayer.Builder(activity, DefaultRenderersFactory(activity).setEnableDecoderFallback(true))
                            .setMediaSourceFactory(sources)
                            .build()
                        player = exo
                        exo.addListener(observation)
                        // This callback is on the playback thread; all other observations are on Main.
                        exo.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
                            observation.frameTimeUs.set(presentationTimeUs)
                        }
                        view = PlayerView(activity).apply {
                            useController = false
                            keepScreenOn = true
                            this.player = exo
                        }
                        // An actual attached SurfaceView exercises decoding/rendering on the emulator.
                        activity.setContentView(view)
                        exo.setMediaItem(MediaItem.fromUri(PlayerActivity.smbUri(config, movie.path)))
                        exo.prepare()
                        exo.play()
                    }
                    val exo = checkNotNull(player)
                    awaitPlayback(exo, observation, positionMs = 500)
                    withContext(Dispatchers.Main) {
                        assertTrue("The scanned movie must expose a seekable timeline", exo.isCurrentMediaItemSeekable)
                        exo.seekTo(8_000)
                        exo.play()
                    }
                    awaitPlayback(exo, observation, positionMs = 8_500)
                    withContext(Dispatchers.Main) {
                        assertTrue("Media3 must acknowledge the requested seek", observation.seekObserved)
                        observation.throwIfFailed()
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        view?.player = null
                        player?.release()
                    }
                }
            }
        } finally {
            smb.reset(config.id)
        }
    }

    private suspend fun awaitPlayback(player: ExoPlayer, observation: PlaybackObservation, positionMs: Long) {
        var snapshot = "Player not observed"
        val advanced = withTimeoutOrNull(40_000) {
            while (true) {
                val reached = withContext(Dispatchers.Main) {
                    observation.throwIfFailed()
                    val frameUs = observation.frameTimeUs.get()
                    snapshot = "state=${player.playbackState}, playing=${player.isPlaying}, " +
                        "position=${player.currentPosition}, videoFrameUs=$frameUs, firstFrame=${observation.firstFrame}"
                    observation.firstFrame && player.isPlaying && player.currentPosition >= positionMs &&
                        frameUs >= positionMs * 1_000
                }
                if (reached) return@withTimeoutOrNull true
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
        assertTrue("SMB playback did not advance past $positionMs ms: $snapshot", advanced)
    }

    private class PlaybackObservation : Player.Listener {
        val frameTimeUs = AtomicLong(-1)
        var firstFrame = false
        var seekObserved = false
        private var error: PlaybackException? = null

        override fun onRenderedFirstFrame() { firstFrame = true }

        override fun onPlayerError(error: PlaybackException) { this.error = error }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seekObserved = true
        }

        fun throwIfFailed() {
            error?.let { throw AssertionError("Media3 failed to play the SMB fixture: ${it.errorCodeName}", it) }
        }
    }
}
