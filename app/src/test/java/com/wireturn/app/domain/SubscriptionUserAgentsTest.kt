package com.wireturn.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionUserAgentsTest {
    private val own = "WireTurn/9.15"

    @Test
    fun resolve_autoAndWireTurnAreTheAppsOwn() {
        assertEquals(own, SubscriptionUserAgents.resolve(SubscriptionUserAgents.AUTO, own))
        assertEquals(own, SubscriptionUserAgents.resolve(SubscriptionUserAgents.WIRETURN, own))
        assertEquals(own, SubscriptionUserAgents.resolve("  ", own))
    }

    @Test
    fun resolve_anythingElseGoesOutAsItIs() {
        assertEquals("Turnable-Android/1.0", SubscriptionUserAgents.resolve(SubscriptionUserAgents.TURNABLE, own))
        assertEquals("v2rayNG/1.10.5", SubscriptionUserAgents.resolve(" v2rayNG/1.10.5 ", own))
    }

    @Test
    fun normalize_blankIsAuto() {
        assertNull(SubscriptionUserAgents.normalize(null))
        assertNull(SubscriptionUserAgents.normalize("   "))
        assertEquals("csqtt-client", SubscriptionUserAgents.normalize(" csqtt-client "))
    }

    @Test
    fun autoFallbacks_areThePresetsAfterWireTurn() {
        assertEquals(
            SubscriptionUserAgents.presets.drop(2),
            SubscriptionUserAgents.autoFallbacks
        )
    }
}
