package com.dineshdevkota.cinevault.smb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NasBrowserIntegrationTest {
    private fun fixture(): SourceConfig {
        val host = InstrumentationRegistry.getArguments().getString("nasHost")
        assumeTrue("Pass nasHost to run the SMB fixture tests", !host.isNullOrBlank())
        return SourceConfig(UUID.randomUUID().toString(), host!!, 1445, "CINEVAULT", "Movies", "cinevault", "fixture-only")
    }
    @Test fun listsSharesAndNestedFoldersWithoutFiles() = runBlocking {
        val config = fixture()
        val browser = NasBrowser()
        val shares = browser.listShares(config).map { it.name }
        assertTrue("CINEVAULT" in shares)
        assertFalse("IPC$" in shares)
        assertEquals(listOf("MediaChecks", "Movies"), browser.listFolders(config.copy(rootPath = "")))
        assertTrue(browser.listFolders(config).containsAll(listOf("Feature (2020)", "Extras", "@eaDir")))
        assertEquals(emptyList<String>(), browser.listFolders(config.copy(rootPath = "Movies/Feature (2020)")))
    }
    @Test fun rejectsBadCredentialsAndAllowsCorrectedRetry() = runBlocking {
        val config = fixture()
        val browser = NasBrowser()
        val failure = runCatching { browser.listShares(config.copy(password = "incorrect-fixture-password")) }.exceptionOrNull()
        assertNotNull("Wrong credentials must not become an empty successful result", failure)
        assertTrue(browser.listShares(config).any { it.name == "CINEVAULT" })
    }
    @Test fun mediaTraversalReportsFilesBeforeReturningAndIncludesShortVideos() = runBlocking {
        val config = fixture()
        val smb = SmbService()
        val reported = mutableListOf<String>()
        try {
            val files = smb.scanMedia(config) { progress -> reported += progress.files.map { it.path } }
            assertEquals(files.map { it.path }.toSet(), reported.toSet())
            assertTrue(files.any { it.path.endsWith("Too Small.mp4") })
            assertTrue(files.any { it.path.endsWith("Feature (2020).mp4") })
            assertFalse(files.any { it.path.contains("Extras/") || it.path.contains("@eaDir/") })
        } finally { smb.reset(config.id) }
    }

    @Test fun typeScriptIsRejectedAndExclusionsApplyBeforeProgressIsReported() = runBlocking {
        val config = fixture().copy(rootPath = "MediaChecks")
        val smb = SmbService()
        try {
            val files = smb.scanMedia(config) { }
            assertEquals(setOf("MediaChecks/clip.ts", "MediaChecks/Keep.mp4", "MediaChecks/Ignored/Hidden.mp4"),
                files.map { it.path }.toSet())
            val excluded = config.copy(ignoredFolders = listOf("Ignored"), ignoredFiles = listOf("Keep.mp4"))
            val reported = mutableListOf<String>()
            val filtered = smb.scanMedia(excluded) { progress -> reported += progress.files.map { it.path } }
            assertEquals(listOf("MediaChecks/clip.ts"), filtered.map { it.path })
            assertEquals(listOf("MediaChecks/clip.ts"), reported)
            val entries = NasBrowser().listEntries(config)
            assertTrue(entries.any { it.name == "Ignored" && it.isDirectory })
            assertTrue(entries.any { it.name == "Keep.mp4" && !it.isDirectory })
        } finally { smb.reset(config.id) }
    }
}
