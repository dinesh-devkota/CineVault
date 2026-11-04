package com.dineshdevkota.cinevault.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Orders progress writes across player instances, while allowing them to outlive an Activity. */
class PlaybackProgressWriter(private val scope: CoroutineScope) {
    private var pending: Job? = null

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
