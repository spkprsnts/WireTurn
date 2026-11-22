package com.wireturn.app.data.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TurnableConfigTest {

    private val config = TurnableConfig(
        userUuid = "u",
        callId = "call",
        gateway = "gw.example:443",
        routes = listOf(TurnableRoute(routeId = "r1", name = "r1", socket = "udp")),
        selectedRouteId = "r1",
        manualCaptcha = true
    )

    @Test
    fun manualCaptcha_staysOutOfTheLinkTurnableReads() {
        assertFalse(config.toUri(true).contains("manual", ignoreCase = true))
    }

    @Test
    fun manualCaptcha_survivesALinkParsedOverTheCurrentConfig() {
        val parsed = TurnableConfig.parse(config.copy(manualCaptcha = false).toUri(), current = config)
        assertEquals(true, parsed?.manualCaptcha)
    }
}
