package com.dineshdevkota.cinevault.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dineshdevkota.cinevault.smb.SmbService
import com.dineshdevkota.cinevault.smb.SourceConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses scripts/nas_fixture.py on the runner, reached through the emulator's host gateway. */
@RunWith(AndroidJUnit4::class)
class SmbIntegrationTest {
    private fun fixture(): SourceConfig {
        val host = InstrumentationRegistry.getArguments().getString("nasHost")
        assumeTrue("Pass nasHost to run the authenticated SMB fixture tests", !host.isNullOrBlank())
        return SourceConfig(UUID.randomUUID().toString(), host!!, 1445, "CINEVAULT", "Movies", "cinevault", "fixture-only")
    }

    @Test
    fun authenticatedScanFiltersExtrasAndStreamsAByteRange() = runBlocking {
        val config = fixture()
        val smb = SmbService()
        try {
            val files = smb.scanVideos(config)
            assertEquals(listOf("Movies/Feature (2020)/Feature (2020).mp4"), files.map { it.path })
            assertEquals(60L * 1024 * 1024, files.single().size)
            smb.open(config, "Movies/Feature (2020)/bytes.bin").use { file ->
                file.stream(3).use { stream ->
                    assertArrayEquals("3456".toByteArray(), ByteArray(4).also { assertEquals(4, stream.read(it)) })
                }
            }
        } finally { smb.reset(config.id) }
    }

    @Test
    fun media3ReadsBoundedRangeThroughRealSmbAndStopsAtEof() = runBlocking {
        val config = fixture()
        val smb = SmbService()
        val source = SmbDataSource { smb.open(config, "Movies/Feature (2020)/bytes.bin") }
        try {
            val spec = DataSpec.Builder().setUri(Uri.parse("smb://fixture/bytes.bin"))
                .setPosition(8).setLength(100).build()
            assertEquals(100L, source.open(spec))
            val bytes = ByteArray(4)
            assertEquals(2, source.read(bytes, 0, bytes.size))
            assertArrayEquals("89".toByteArray(), bytes.copyOf(2))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, bytes.size))
        } finally {
            source.close()
            smb.reset(config.id)
        }
    }

    @Test
    fun repeatedResetReconnectKeepsExistingPlaybackHandleReadable() = runBlocking {
        val config = fixture()
        val smb = SmbService()
        val playing = smb.open(config, "Movies/Feature (2020)/bytes.bin")
        try {
            repeat(4) {
                smb.reset(config.id)
                assertEquals(1, smb.scanVideos(config).size)
                playing.stream(3).use { stream ->
                    val bytes = ByteArray(4)
                    assertEquals(4, stream.read(bytes))
                    assertArrayEquals("3456".toByteArray(), bytes)
                }
            }
        } finally {
            playing.close()
            smb.reset(config.id)
        }
    }

    @Test
    fun rejectedCredentialsDoNotPoisonCorrectCredentialsAndResetAllowsReconnect() = runBlocking {
        val config = fixture()
        val smb = SmbService()
        try {
            val failure = runCatching { smb.testConnection(config.copy(password = "wrong-fixture-password")) }.exceptionOrNull()
            assertNotNull("The fixture requires authentication", failure)
            assertTrue(SmbService.describe(failure!!).contains("rejected"))
            smb.testConnection(config)
            smb.reset(config.id)
            assertEquals(1, smb.scanVideos(config).size)
        } finally { smb.reset(config.id) }
    }
}
