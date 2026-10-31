package com.dineshdevkota.cinevault.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

class SmbDataSourceTest {
    private val toEnd = C.LENGTH_UNSET.toLong()

    @Test
    fun servesToTheEndOfTheFileWhenLengthIsUnset() {
        assertEquals(1000L, SmbDataSource.bytesToServe(0, toEnd, 1000))
        assertEquals(400L, SmbDataSource.bytesToServe(600, toEnd, 1000))
        assertEquals(0L, SmbDataSource.bytesToServe(1000, toEnd, 1000))
    }

    @Test
    fun clampsRequestedRangesToTheFile() {
        assertEquals(100L, SmbDataSource.bytesToServe(10, 100, 1000))
        assertEquals(50L, SmbDataSource.bytesToServe(950, 100, 1000))
    }

    @Test
    fun rejectsPositionsPastTheEnd() {
        val error = assertThrows(DataSourceException::class.java) { SmbDataSource.bytesToServe(1001, toEnd, 1000) }
        assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, error.reason)
    }

    @Test
    fun mapsSmbStatusesToPlaybackErrorCodes() {
        fun reason(status: Long) =
            SmbDataSource.asDataSourceException(SMBApiException(status, SMB2MessageCommandCode.SMB2_CREATE, null)).reason
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, reason(0xC0000034L))
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, reason(0xC000003AL))
        assertEquals(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, reason(0xC0000022L))
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, reason(0xC000035CL))
    }

    @Test
    fun describesNetworkFailures() {
        val error = SmbDataSource.asDataSourceException(IOException("read failed", ConnectException("refused")))
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, error.reason)
        assertEquals("Could not reach the NAS. Check that it is on and on the same network.", error.message)
    }

    @Test
    fun keepsExistingDataSourceExceptions() {
        val original = DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertSame(original, SmbDataSource.asDataSourceException(original))
    }
}
