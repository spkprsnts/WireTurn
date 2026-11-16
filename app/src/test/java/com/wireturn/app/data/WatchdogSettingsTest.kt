package com.wireturn.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchdogSettingsTest {

    private val fifteenMinutes = 15 * 60_000L

    @Test
    fun eternalDelay_doublesFrom30sUpToTheMaximum() {
        val delays = (1..7).map { WatchdogSettings.eternalDelayMs(it, fifteenMinutes) }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L), delays)
    }

    @Test
    fun eternalDelay_neverOverflowsOnALongOutage() {
        assertEquals(fifteenMinutes, WatchdogSettings.eternalDelayMs(10_000, fifteenMinutes))
        val day = 1440 * 60_000L
        // The doubling stops at 2^16 * 30s, well past a day.
        assertEquals(day, WatchdogSettings.eternalDelayMs(10_000, day))
    }

    @Test
    fun eternalDelay_firstAttemptPastTheLimitIs30s() {
        assertEquals(30_000L, WatchdogSettings.eternalDelayMs(0, fifteenMinutes))
    }

    @Test
    fun maxInterval_isClampedToItsRange() {
        assertEquals(60_000L, WatchdogSettings(maxIntervalMinutes = 0).maxIntervalMs)
        assertEquals(1440 * 60_000L, WatchdogSettings(maxIntervalMinutes = 100_000).maxIntervalMs)
        assertEquals(fifteenMinutes, WatchdogSettings().maxIntervalMs)
    }
}
