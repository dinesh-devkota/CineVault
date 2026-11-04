package com.dineshdevkota.cinevault.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

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

    @Test
    fun manualResetWaitsForOlderSaveAndCannotBeUndoneByIt() = runTest {
        val writer = PlaybackProgressWriter(this)
        val releaseSave = CompletableDeferred<Unit>()
        var position = 30
        writer.enqueue {
            releaseSave.await()
            position = 500
        }
        val reset = async(start = CoroutineStart.UNDISPATCHED) { writer.await { position = 0 } }
        runCurrent()
        assertFalse(reset.isCompleted)
        releaseSave.complete(Unit)
        reset.await()
        assertEquals(0, position)
    }

    @Test
    fun manualMarkWatchedWaitsForOlderResumeSave() = runTest {
        val writer = PlaybackProgressWriter(this)
        val releaseSave = CompletableDeferred<Unit>()
        var watched = false
        writer.enqueue {
            releaseSave.await()
            watched = false
        }
        val mark = async(start = CoroutineStart.UNDISPATCHED) { writer.await { watched = true } }
        runCurrent()
        assertFalse(mark.isCompleted)
        releaseSave.complete(Unit)
        mark.await()
        assertEquals(true, watched)
    }

    @Test
    fun quickResumeReadsThePositionFromThePreviousPlayersPendingSave() = runTest {
        val writer = PlaybackProgressWriter(this)
        val releaseSave = CompletableDeferred<Unit>()
        var storedPosition = 30
        writer.enqueue {
            releaseSave.await()
            storedPosition = 500
        }
        val resume = async(start = CoroutineStart.UNDISPATCHED) { writer.await { storedPosition } }
        runCurrent()
        assertFalse(resume.isCompleted)
        releaseSave.complete(Unit)
        assertEquals(500, resume.await())
    }

    @Test
    fun manualEditPropagatesItsFailureAndDoesNotBlockTheNextOperation() = runTest {
        supervisorScope {
            val writer = PlaybackProgressWriter(this)
            val expected = IOException("Database write failed")
            try {
                writer.await<Unit> { throw expected }
                fail("The caller must not report success after a failed edit")
            } catch (actual: IOException) {
                assertEquals(expected.message, actual.message)
            }
            assertEquals(42, writer.await { 42 })
        }
    }

}
