package com.wireturn.app

import com.wireturn.app.SplitTunnelGuard.Policy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelGuardTest {

    private val me = 10_100
    private val browser = 10_200
    private val detector = 10_300
    private val tcp = 6
    private val udp = 17

    private fun decide(p: Policy, uid: Int, proto: Int = tcp, dstPort: Int = 443) =
        SplitTunnelGuard.decide(p, uid, me, proto, dstPort)

    @Test
    fun allowListKeepsOutAppsNotOnIt() {
        val p = Policy(allowed = setOf(browser), denied = emptySet())
        assertTrue(decide(p, browser))
        assertFalse(decide(p, detector))
        assertFalse(decide(p, detector, udp, 3478))
    }

    @Test
    fun bypassListKeepsOutOnlyItsApps() {
        val p = Policy(allowed = null, denied = setOf(detector))
        assertTrue(decide(p, browser))
        assertFalse(decide(p, detector))
    }

    @Test
    fun ownAppIsAlwaysOut() {
        assertFalse(decide(Policy(allowed = null, denied = emptySet()), me))
        assertFalse(decide(Policy(allowed = setOf(me), denied = emptySet()), me))
    }

    @Test
    fun systemUidsPassUnlessExcludedExplicitly() {
        val netdDns = 1051
        assertTrue(decide(Policy(allowed = setOf(browser), denied = emptySet()), netdDns, udp, 53))
        assertTrue(decide(Policy(allowed = setOf(browser), denied = emptySet()), 0))
        assertFalse(decide(Policy(allowed = null, denied = setOf(1001)), 1001))
    }

    @Test
    fun unknownOwnerOnlyPassesForDns() {
        val p = Policy(allowed = null, denied = emptySet())
        assertFalse(decide(p, -1))
        assertFalse(decide(p, -1, udp, 443))
        assertTrue(decide(p, -1, udp, 53))
    }

    @Test
    fun appsOfAnotherUserAreMatchedByFullUid() {
        val p = Policy(allowed = setOf(browser), denied = emptySet())
        assertFalse(decide(p, 10 * 100_000 + browser))
    }
}
