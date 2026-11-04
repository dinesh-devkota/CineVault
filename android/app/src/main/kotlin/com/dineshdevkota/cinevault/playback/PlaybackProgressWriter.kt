package com.dineshdevkota.cinevault.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Orders player writes, manual progress edits, and resume reads across Activity instances. */
class PlaybackProgressWriter(private val scope: CoroutineScope) {
    private var pending: Job? = null

    /** Manual edits and resume reads wait for earlier writes and propagate their own failures. */
    suspend fun <T> await(operation: suspend () -> T): T = enqueueAwaitable(operation).await()

    @Synchronized
    private fun <T> enqueueAwaitable(operation: suspend () -> T): Deferred<T> {
        val previous = pending
        return scope.async {
            previous?.join()
            operation()
        }.also { pending = it }
    }

    @Synchronized
    fun enqueue(write: suspend () -> Unit): Job {
        val previous = pending
        return scope.launch {
            // A slow earlier resume save must finish before a later save or watched update.
            // join also lets the queue continue if an earlier write failed or was cancelled.
            previous?.join()
            write()
        }.also { pending = it }
    }
}
