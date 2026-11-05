@file:OptIn(UnstableApi::class)

package com.dineshdevkota.cinevault.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Pair
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.room.withTransaction
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.dineshdevkota.cinevault.container
import com.dineshdevkota.cinevault.core.WatchRules
import com.dineshdevkota.cinevault.data.MovieEntity
import com.dineshdevkota.cinevault.smb.SmbException
import com.dineshdevkota.cinevault.smb.SmbService
import com.dineshdevkota.cinevault.smb.SourceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen ExoPlayer for one movie, streamed straight from the NAS. Saves the resume point every
 * 10 seconds, on pause and on exit, and marks the movie watched past [WatchRules.WATCHED_FRACTION].
 */
class PlayerActivity : ComponentActivity() {
    private class Target(val movie: MovieEntity, val config: SourceConfig)

    private lateinit var playerView: PlayerView
    private lateinit var information: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var detailLabel: TextView
    private var target: Target? = null
    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var startPositionMs = C.TIME_UNSET
    private var playWhenReady = true
    private var finishedPlayback = false
    private var warnedAboutTracks = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Paused, the TV may dim or start its screensaver as usual.
            playerView.keepScreenOn = isPlaying
            if (!isPlaying) record()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                markFinished()
                finish()
            }
        }

        override fun onTracksChanged(tracks: Tracks) = warnAboutUnsupportedTracks(tracks)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowSubtitleButton(true)
            setErrorMessageProvider { error -> Pair.create(0, errorMessage(error)) }
            // Focused, the view gets D-pad presses (first one shows the controls) and remote media keys.
            isFocusable = true
        }
        val spacing = (24 * resources.displayMetrics.density).toInt()
        titleLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 24f; maxLines = 2 }
        detailLabel = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 15f; maxLines = 3 }
        information = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD9000000.toInt(), Color.TRANSPARENT))
            isFocusable = false
            addView(LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(titleLabel, LinearLayout.LayoutParams(0, -2, 1f))
                addView(android.widget.Button(this@PlayerActivity).apply {
                    setText(com.dineshdevkota.cinevault.R.string.back)
                    contentDescription = "Back to media details"
                    setOnClickListener { finish() }
                })
            })
            addView(detailLabel)
        }
        setContentView(FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(information, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        })
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> information.visibility = visibility })
        playerView.requestFocus()
        val movieId = intent.getStringExtra(EXTRA_MOVIE_ID) ?: return finish()
        val resume = intent.getBooleanExtra(EXTRA_RESUME, false)
        savedInstanceState?.let {
            startPositionMs = it.getLong(STATE_POSITION, C.TIME_UNSET)
            playWhenReady = it.getBoolean(STATE_PLAY_WHEN_READY, true)
        }
        onBackPressedDispatcher.addCallback(this) {
            // First Back hides the on-screen controls, the next one leaves the player.
            if (playerView.isControllerFullyVisible && player?.playerError == null) playerView.hideController() else finish()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val loaded = try {
                    load(movieId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(this@PlayerActivity, SmbService.describe(e), Toast.LENGTH_LONG).show()
                    finish()
                    return@repeatOnLifecycle
                }
                if (startPositionMs == C.TIME_UNSET) startPositionMs = if (resume) loaded.movie.playbackPositionMs else 0
                target = loaded
                showInformation(loaded.movie)
                startPlayer()
                container.database.movies().observe(movieId).collect { current ->
                    if (current == null || PlaybackValidator.rejectionReason(current, loaded.config) != null) {
                        stopPlayer()
                        target = null
                        Toast.makeText(this@PlayerActivity, "This item is no longer available for playback.", Toast.LENGTH_LONG).show()
                        finish()
                    } else showInformation(current)
                }
            }
        }
    }

    private suspend fun load(movieId: String): Target {
        val database = container.database
        val movie = container.playbackProgress.await { database.movies().get(movieId) }
            ?: throw SmbException("This movie is no longer in the library.")
        PlaybackValidator.rejectionReason(movie)?.let { throw SmbException(it) }
        val source = database.sources().get(movie.sourceId) ?: throw SmbException("This movie's NAS source was removed.")
        val config = withContext(Dispatchers.Default) { source.config(container.secrets) }
        PlaybackValidator.validate(movie, config, container.smb::hasPlayableContent)
        // A source edit or ignore action can complete while the NAS header read is suspended.
        return database.withTransaction {
            val currentSource = database.sources().get(source.id)
            if (currentSource?.copy(name = source.name, lastScanAt = source.lastScanAt) != source) {
                throw SmbException("The NAS source changed. Reopen this item to play it.")
            }
            val current = database.movies().get(movieId) ?: throw SmbException("This item was removed from the library.")
            if (current.filePath != movie.filePath || current.sourceId != movie.sourceId) {
                throw SmbException("This item's source changed. Reopen it to play.")
            }
            PlaybackValidator.rejectionReason(current, config)?.let { throw SmbException(it) }
            Target(current, config)
        }
    }

    override fun onStop() {
        super.onStop()
        stopPlayer()
        target = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_POSITION, player?.currentPosition ?: startPositionMs)
        outState.putBoolean(STATE_PLAY_WHEN_READY, player?.playWhenReady ?: playWhenReady)
    }

    private fun startPlayer() {
        val target = target ?: return
        if (player != null || isFinishing) return
        val smb = container.smb
        val files = DataSource.Factory { SmbDataSource { smb.open(target.config, target.movie.filePath) } }
        val mediaSources = ProgressiveMediaSource.Factory(files, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
            .setLoadErrorHandlingPolicy(SmbDataSource.loadErrorPolicy)
        val exo = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        exo.addListener(listener)
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(smbUri(target.config, target.movie.filePath))
                .setMediaId(target.movie.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(target.movie.title)
                    .setDescription(target.movie.overview)
                    .setArtworkUri(com.dineshdevkota.cinevault.core.Tmdb.imageUrl(target.movie.posterPath)?.let(Uri::parse))
                    .build())
                .build(),
            startPositionMs.coerceAtLeast(0),
        )
        exo.playWhenReady = playWhenReady
        exo.prepare()
        playerView.player = exo
        player = exo
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (exo.isPlaying) record()
            }
        }
    }

    private fun stopPlayer() {
        val exo = player ?: return
        progressJob?.cancel()
        progressJob = null
        record()
        startPositionMs = exo.currentPosition
        playWhenReady = exo.playWhenReady
        exo.removeListener(listener)
        playerView.player = null
        exo.release()
        player = null
    }

    private fun showInformation(movie: MovieEntity) {
        titleLabel.text = movie.title.ifBlank { movie.fileName }
        val episode = movie.episodeInfo?.let { "${movie.seriesTitle ?: it.seriesTitle} · Season ${movie.seasonNumber ?: it.season} · Episode ${movie.episodeNumber ?: it.episode}" }
        val facts = listOfNotNull(episode, movie.year?.toString(), movie.genres.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            movie.runtimeMinutes?.let { "$it min" }).joinToString(" · ")
        detailLabel.text = listOf(facts, movie.overview.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** Persists the resume point, or marks the movie watched once playback passes the watched threshold. */
    private fun record() {
        val exo = player ?: return
        val movieId = target?.movie?.id ?: return
        if (finishedPlayback) return
        val position = exo.currentPosition
        if (position <= 0) return
        val duration = knownDuration(exo)
        if (WatchRules.reachedEnd(position, duration)) return markFinished()
        val now = System.currentTimeMillis()
        val movies = container.database.movies()
        container.playbackProgress.enqueue { movies.saveProgress(movieId, position, duration, now) }
    }

    private fun markFinished() {
        val movieId = target?.movie?.id ?: return
        if (finishedPlayback) return
        finishedPlayback = true
        val duration = player?.let(::knownDuration) ?: 0L
        val now = System.currentTimeMillis()
        val movies = container.database.movies()
        container.playbackProgress.enqueue { movies.markFinished(movieId, duration, now) }
    }

    private fun knownDuration(exo: ExoPlayer): Long = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    /** ExoPlayer silently skips tracks the TV can't decode (e.g. DTS audio without a receiver); say so. */
    private fun warnAboutUnsupportedTracks(tracks: Tracks) {
        if (warnedAboutTracks || tracks.isEmpty) return
        val unsupported = listOfNotNull(
            "video".takeIf { tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO) },
            "audio".takeIf { tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO) },
        )
        if (unsupported.isEmpty()) return
        warnedAboutTracks = true
        val message = "This TV can't decode the ${unsupported.joinToString(" or ")} format of this file."
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun errorMessage(error: PlaybackException): String = when (error.errorCode / 1000) {
        // Media3 groups error codes by category in thousands: 2xxx I/O, 3xxx parsing, 4xxx decoding, 5xxx audio.
        2 -> error.cause?.let { SmbService.describe(it) } ?: "Could not read the file from the NAS."
        3 -> "This file's format isn't supported, or the file is damaged."
        4, 5 -> "This TV can't play this file's video or audio format."
        else -> error.message ?: "Playback failed."
    }

    companion object {
        private const val EXTRA_MOVIE_ID = "movie_id"
        private const val EXTRA_RESUME = "resume"
        private const val STATE_POSITION = "position"
        private const val STATE_PLAY_WHEN_READY = "play_when_ready"
        private const val PROGRESS_INTERVAL_MS = 10_000L
        private const val SEEK_BACK_MS = 10_000L
        private const val SEEK_FORWARD_MS = 30_000L

        fun start(context: Context, movieId: String, resume: Boolean) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_MOVIE_ID, movieId)
                    .putExtra(EXTRA_RESUME, resume),
            )
        }

        /** Display URI (no credentials); its file extension helps ExoPlayer pick the right extractor first. */
        fun smbUri(config: SourceConfig, path: String): Uri = Uri.Builder()
            .scheme("smb")
            .authority(SmbService.normalizedHost(config.host))
            .apply { (config.share.split('/', '\\') + path.split('/')).filter { it.isNotEmpty() }.forEach(::appendPath) }
            .build()
    }
}
