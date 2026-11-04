package com.dineshdevkota.cinevault.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dineshdevkota.cinevault.smb.RemoteHandle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Exercises the actual Media3 bridge, including Android Uri and DataSpec, on a runtime. */
@RunWith(AndroidJUnit4::class)
class SmbDataSourceContractTest {
    private val uri = Uri.parse("smb://test/Movies/movie.mp4")

    private class FakeHandle(private val contents: ByteArray = byteArrayOf(0, 1, 2, 3)) : RemoteHandle {
        override val length = contents.size.toLong()
        var closed = false
        var streamClosed = false
        var openError: RuntimeException? = null
        override fun stream(position: Long): InputStream {
            openError?.let { throw it }
            return object : ByteArrayInputStream(contents, position.toInt(), contents.size - position.toInt()) {
                override fun close() { streamClosed = true; super.close() }
            }
        }
        override fun close() { closed = true }
    }

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()

    @Test
    fun boundedOpenReportsRequestedLengthButReadsOnlyAvailableBytes() {
        val file = FakeHandle()
        val source = SmbDataSource { file }
        try {
            assertEquals(20L, source.open(spec(position = 2, length = 20)))
            val bytes = ByteArray(10)
            assertEquals(2, source.read(bytes, 0, bytes.size))
            assertArrayEquals(byteArrayOf(2, 3), bytes.copyOf(2))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, bytes.size))
        } finally { source.close() }
        assertTrue(file.closed)
        assertTrue(file.streamClosed)
        assertNull(source.uri)
    }

    @Test
    fun unboundedOpenResolvesLengthAndEndPositionSucceeds() {
        val source = SmbDataSource { FakeHandle() }
        try {
            assertEquals(2L, source.open(spec(position = 2)))
        } finally { source.close() }
        try {
            assertEquals(0L, source.open(spec(position = 4)))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
            assertEquals(0, source.read(ByteArray(0), 0, 0))
        } finally { source.close() }
    }

    @Test
    fun boundedOpenAtEndStillReportsRequestedLengthAndImmediatelyEnds() {
        val source = SmbDataSource { FakeHandle() }
        try {
            assertEquals(10L, source.open(spec(position = 4, length = 10)))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        } finally { source.close() }
    }

    @Test
    fun outOfRangeOpenIsClassifiedAndCloseReleasesThePartialOpen() {
        val file = FakeHandle()
        val source = SmbDataSource { file }
        try {
            val error = assertThrows(DataSourceException::class.java) { source.open(spec(position = 5)) }
            assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, error.reason)
        } finally { source.close() }
        assertTrue(file.closed)
    }

    @Test
    fun streamSetupRuntimeFailureIsWrappedForMedia3AndCanBeClosed() {
        val file = FakeHandle().apply { openError = IllegalStateException("session disconnected") }
        val source = SmbDataSource { file }
        try {
            assertThrows(IOException::class.java) { source.open(spec()) }
        } finally { source.close() }
        assertTrue(file.closed)
    }
}
