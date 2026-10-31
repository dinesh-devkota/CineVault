@file:OptIn(UnstableApi::class)

package com.dineshdevkota.cinevault.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.dineshdevkota.cinevault.smb.RemoteHandle
import com.dineshdevkota.cinevault.smb.SmbService
import com.hierynomus.mssmb2.SMBApiException
import java.io.InputStream

/**
 * Streams one NAS file to ExoPlayer. Every open() starts a read at the requested byte offset, so
 * playback and seeking never download the whole file (the tvOS SMBResourceLoader equivalent).
 */
class SmbDataSource(private val openFile: () -> RemoteHandle) : BaseDataSource(/* isNetwork = */ true) {
    private var handle: RemoteHandle? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val file = try {
            openFile()
        } catch (e: Exception) {
            throw asDataSourceException(e)
        }
        handle = file
        bytesRemaining = bytesToServe(dataSpec.position, dataSpec.length, file.length)
        input = file.stream(dataSpec.position)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val read = try {
            checkNotNull(input).read(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        } catch (e: Exception) {
            throw asDataSourceException(e)
        }
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        input = null
        val file = handle
        handle = null
        // Closing talks to the NAS; failing to release a handle must not fail playback.
        runCatching { file?.close() }
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    companion object {
        private val fatalReasons = setOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )

        /** Retries network blips like the default policy, but fails fast when retrying can't help. */
        val loadErrorPolicy: LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val error = loadErrorInfo.exception
                return if (error is DataSourceException && error.reason in fatalReasons) {
                    C.TIME_UNSET
                } else {
                    super.getRetryDelayMsFor(loadErrorInfo)
                }
            }
        }

        /** Bytes to serve from [position]; [requested] is a byte count or C.LENGTH_UNSET for "to the end". */
        fun bytesToServe(position: Long, requested: Long, fileLength: Long): Long {
            if (position > fileLength) {
                throw DataSourceException(
                    "The file on the NAS is shorter than expected. Rescan the library and try again.",
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                )
            }
            val available = fileLength - position
            return if (requested == C.LENGTH_UNSET.toLong()) available else minOf(requested, available)
        }

        /** Wraps SMB/network failures with a readable message and an error code that drives retries. */
        fun asDataSourceException(error: Exception): DataSourceException {
            if (error is DataSourceException) return error
            val status = generateSequence<Throwable>(error) { it.cause }.take(8)
                .firstNotNullOfOrNull { it as? SMBApiException }?.statusCode
            val reason = when (status) {
                0xC0000034L, 0xC000003AL -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                0xC0000022L, 0xC000006DL -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                else -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            }
            return DataSourceException(SmbService.describe(error), error, reason)
        }
    }
}
