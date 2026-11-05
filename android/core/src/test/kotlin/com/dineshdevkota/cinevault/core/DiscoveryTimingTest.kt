package com.dineshdevkota.cinevault.core

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTimingTest {
    @Test fun coldStartupDoesNotConsumeTheBrowsingWindow() {
        val timing = DiscoveryTiming(requestedAt = 100)
        assertFalse(timing.hasStarted)
        timing.started(8_100)
        assertEquals(15_000L, timing.remaining(8_100, false))
        assertEquals(5_000L, timing.remaining(18_100, false))
        assertEquals(0L, timing.remaining(23_100, false))
    }

    @Test fun lateAddressResolutionCanFinishAfterTheBrowseDeadline() {
        val timing = DiscoveryTiming(0)
        timing.started(0)
        assertEquals(1L, timing.remaining(14_999, true))
        assertEquals(8_000L, timing.remaining(15_000, true))
        assertEquals(6_000L, timing.remaining(17_000, true))
        assertEquals(0L, timing.remaining(17_000, false))
    }

    @Test fun missingStartupAndStalledLookupsHaveBoundedTimeouts() {
        val neverStarted = DiscoveryTiming(0)
        assertEquals(0L, neverStarted.remaining(10_000, true))
        val started = DiscoveryTiming(0)
        started.started(2_000)
        assertEquals(0L, started.remaining(25_000, true))
        started.started(30_000)
        assertEquals(0L, started.remaining(30_000, true))
    }
}
