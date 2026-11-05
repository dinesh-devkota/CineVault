package com.dineshdevkota.cinevault.core

/** A bounded discovery window begins when browsing actually starts, then drains late lookups. */
class DiscoveryTiming(
    private val requestedAt: Long,
    private val startupMs: Long = 10_000,
    private val browseMs: Long = 15_000,
    private val resolutionGraceMs: Long = 8_000,
) {
    private var startedAt: Long? = null
    val hasStarted: Boolean get() = startedAt != null

    fun started(now: Long) {
        if (startedAt == null) startedAt = now
    }

    fun remaining(now: Long, pendingResolution: Boolean): Long {
        val start = startedAt ?: return (requestedAt + startupMs - now).coerceAtLeast(0)
        val browseEnd = start + browseMs
        val end = if (now >= browseEnd && pendingResolution) browseEnd + resolutionGraceMs else browseEnd
        return (end - now).coerceAtLeast(0)
    }
}
