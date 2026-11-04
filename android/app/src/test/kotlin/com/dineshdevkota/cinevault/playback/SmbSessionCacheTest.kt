package com.dineshdevkota.cinevault.playback

import com.dineshdevkota.cinevault.smb.SmbSessionCache
import com.dineshdevkota.cinevault.smb.SourceResetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SmbSessionCacheTest {
    private data class Config(val id: String)
    private class Connection {
        var valid = true
        var closes = 0
    }
    private fun cache(connect: (Config) -> Connection = { Connection() }) = SmbSessionCache(
        sourceId = { config: Config -> config.id },
        isValid = { connection: Connection -> connection.valid },
        connect = connect,
        disconnect = { it.closes++ },
    )

    @Test
    fun resetDuringSetupDiscardsTheCreatedSessionAndPreventsOldConfigRetry() {
        val config = Config("nas")
        val created = Connection()
        var connects = 0
        lateinit var sessions: SmbSessionCache<Config, Connection>
        sessions = cache {
            connects++
            sessions.reset(config.id)
            created
        }
        val operation = sessions.operation(config)
        assertThrows(SourceResetException::class.java) { operation.acquire() }
        assertEquals(1, created.closes)
        assertThrows(SourceResetException::class.java) { operation.acquire() }
        assertEquals(1, connects)
    }

    @Test(timeout = 10_000)
    fun resetWhileAnotherThreadConnectsDiscardsItsSessionWithoutWaitingForSetup() {
        val enteredSetup = CountDownLatch(1)
        val finishSetup = CountDownLatch(1)
        val created = Connection()
        val sessions = cache {
            enteredSetup.countDown()
            check(finishSetup.await(3, TimeUnit.SECONDS)) { "Reset blocked behind connection setup" }
            created
        }
        val operation = sessions.operation(Config("nas"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Throwable?> {
                runCatching { operation.acquire().close() }.exceptionOrNull()
            }
            assertTrue("Connection setup never started", enteredSetup.await(3, TimeUnit.SECONDS))
            // This must return while the connection thread is still blocked on the barrier.
            sessions.reset("nas")
            finishSetup.countDown()
            assertTrue(result.get(3, TimeUnit.SECONDS) is SourceResetException)
            assertEquals(1, created.closes)
        } finally {
            finishSetup.countDown()
            worker.shutdownNow()
            assertTrue("Connection worker did not terminate", worker.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun queuedOperationCannotConnectUsingSettingsCapturedBeforeReset() {
        var connects = 0
        val sessions = cache { connects++; Connection() }
        val operation = sessions.operation(Config("nas"))
        sessions.reset("nas")
        assertThrows(SourceResetException::class.java) { operation.acquire() }
        assertEquals(0, connects)
    }

    @Test
    fun resetDuringBodyRejectsResultAndRetryWithoutClosingOutstandingReader() {
        var connects = 0
        val sessions = cache { connects++; Connection() }
        val config = Config("nas")
        val operation = sessions.operation(config)
        val lease = operation.acquire()
        val file = lease.retain()
        sessions.reset(config.id)
        assertThrows(SourceResetException::class.java) { operation.checkCurrent() }
        assertThrows(SourceResetException::class.java) { operation.acquire() }
        assertEquals(1, connects)
        assertEquals(0, lease.value.closes)
        lease.close()
        assertEquals(0, file.value.closes)
        file.close()
        file.close()
        assertEquals(1, file.value.closes)
    }

    @Test
    fun staleEvictionRetiresOnlyTheFailedSessionAndClosesAfterLastHandle() {
        val sessions = cache()
        val operation = sessions.operation(Config("nas"))
        val old = operation.acquire()
        val file = old.retain()
        operation.invalidate(old)
        val replacement = operation.acquire()
        assertNotSame(old.value, replacement.value)
        assertEquals(0, old.value.closes)
        // A second failure from the old reader must not evict the replacement.
        operation.invalidate(old)
        operation.acquire().use { assertSame(replacement.value, it.value) }
        old.close()
        assertEquals(0, file.value.closes)
        file.close()
        assertEquals(1, file.value.closes)
        sessions.reset("nas")
        assertEquals(0, replacement.value.closes)
        replacement.close()
        assertEquals(1, replacement.value.closes)
    }

    @Test
    fun disconnectedCacheEntryWaitsForItsReadersWhileReplacementConnects() {
        val sessions = cache()
        val operation = sessions.operation(Config("nas"))
        val old = operation.acquire()
        old.value.valid = false
        val replacement = operation.acquire()
        assertNotSame(old.value, replacement.value)
        assertEquals(0, old.value.closes)
        old.close()
        assertEquals(1, old.value.closes)
        sessions.reset("nas")
        replacement.close()
        assertEquals(1, replacement.value.closes)
    }

    @Test
    fun resetDoesNotInvalidateOtherSources() {
        val sessions = cache()
        val other = sessions.operation(Config("other"))
        val lease = other.acquire()
        sessions.reset("nas")
        other.checkCurrent()
        other.acquire().use { assertSame(lease.value, it.value) }
        lease.close()
        sessions.reset("other")
        assertEquals(1, lease.value.closes)
    }
}
