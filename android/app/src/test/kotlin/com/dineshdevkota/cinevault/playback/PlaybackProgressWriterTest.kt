package com.dineshdevkota.cinevault.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackProgressWriterTest {
    @Test
    fun slowResumeSaveCannotOverwriteTheLaterWatchedUpdate() = runTest {
        val writer = PlaybackProgressWriter(this)
        val releaseSave = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        writer.enqueue {
            releaseSave.await()
            writes += "resume at 50%"
        }
        val finished = writer.enqueue { writes += "watched" }
        runCurrent()
        assertEquals(emptyList<String>(), writes)
        releaseSave.complete(Unit)
        finished.join()
        assertEquals(listOf("resume at 50%", "watched"), writes)
    }

    @Test
    fun writesFromTheNextPlayerWaitForThePreviousPlayersFinalSave() = runTest {
        val writer = PlaybackProgressWriter(this)
        val releaseSave = CompletableDeferred<Unit>()
        var savedPosition = 0
        writer.enqueue {
            releaseSave.await()
            savedPosition = 500
        }
        val restartedPlayer = writer.enqueue { savedPosition = 10 }
        runCurrent()
        releaseSave.complete(Unit)
        restartedPlayer.join()
        assertEquals(10, savedPosition)
    }
}
