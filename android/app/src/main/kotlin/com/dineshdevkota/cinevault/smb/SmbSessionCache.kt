package com.dineshdevkota.cinevault.smb

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A source edit/reset invalidated the settings captured by an in-flight operation. */
internal class SourceResetException : IOException("The NAS source changed while it was in use. Try again.")

/** Keeps connection setup, invalidation, and the lifetime of outstanding SMB reads in one place. */
internal class SmbSessionCache<K : Any, V : Any>(
    private val sourceId: (K) -> String,
    private val isValid: (V) -> Boolean,
    private val connect: (K) -> V,
    private val disconnect: (V) -> Unit,
) {
    private inner class Source {
        val connectLock = Any()
        var generation = 0L
        var entry: Entry? = null
    }

    private inner class Entry(val key: K, val value: V) {
        private var readers = 0
        private var retired = false
        private var closed = false

        fun borrow(retainExisting: Boolean = false): SessionLease<V>? = synchronized(this) {
            if (closed || (retired && !retainExisting)) return null
            check(!retainExisting || readers > 0)
            readers++
            SessionLease(value, this, { checkNotNull(borrow(retainExisting = true)) }, ::release)
        }

        fun retire() {
            val close = synchronized(this) {
                retired = true
                shouldClose()
            }
            if (close) disconnect(value)
        }

        private fun release() {
            val close = synchronized(this) {
                check(readers > 0)
                readers--
                shouldClose()
            }
            if (close) disconnect(value)
        }

        private fun shouldClose(): Boolean = (retired && readers == 0 && !closed).also { if (it) closed = true }
    }

    private val sources = ConcurrentHashMap<String, Source>()

    inner class Operation internal constructor(val key: K, private val generation: Long) {
        fun checkCurrent() {
            val source = source(key)
            synchronized(source) { if (source.generation != generation) throw SourceResetException() }
        }

        fun acquire(): SessionLease<V> {
            val source = source(key)
            // Serialize setup per source, but let reset advance its generation while IO is blocked.
            synchronized(source.connectLock) {
                val previous = synchronized(source) {
                    checkCurrent()
                    source.entry?.takeIf { it.key == key && isValid(it.value) }?.borrow()?.let { return it }
                    source.entry.also { source.entry = null }
                }
                previous?.retire()
                checkCurrent()
                val created = Entry(key, connect(key))
                synchronized(source) {
                    if (source.generation == generation) {
                        source.entry = created
                        return checkNotNull(created.borrow())
                    }
                }
                // Reset ran while connecting. This session must never enter the cache.
                created.retire()
                throw SourceResetException()
            }
        }

        fun invalidate(lease: SessionLease<V>) {
            val source = source(key)
            val removed = synchronized(source) {
                source.entry?.takeIf { it === lease.identity }?.also { source.entry = null }
            }
            removed?.retire()
        }
    }

    fun operation(key: K): Operation {
        val source = source(key)
        return synchronized(source) { Operation(key, source.generation) }
    }

    fun reset(sourceId: String) {
        val source = sources.getOrPut(sourceId) { Source() }
        val removed = synchronized(source) {
            source.generation++
            source.entry.also { source.entry = null }
        }
        removed?.retire()
    }

    private fun source(key: K): Source = sources.getOrPut(sourceId(key)) { Source() }
}

/** An idempotent operation or playback-handle lease; retiring a cache entry never closes a reader. */
internal class SessionLease<V : Any>(
    val value: V,
    internal val identity: Any,
    private val retainLease: () -> SessionLease<V>,
    private val releaseLease: () -> Unit,
) : Closeable {
    private var released = false

    @Synchronized
    fun retain(): SessionLease<V> {
        check(!released)
        return retainLease()
    }

    override fun close() {
        val release = synchronized(this) {
            if (released) false else { released = true; true }
        }
        if (release) releaseLease()
    }
}
