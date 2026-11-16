package com.wireturn.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress

class LanRoutesTest {

    private fun value(address: String) = BigInteger(1, InetAddress.getByName(address).address)

    private fun covered(routes: List<Pair<String, Int>>, bits: Int, address: String): Boolean {
        val target = value(address)
        return routes.any { (base, len) -> value(base).shiftRight(bits - len) == target.shiftRight(bits - len) }
    }

    private fun size(routes: List<Pair<String, Int>>, bits: Int): BigInteger =
        routes.fold(BigInteger.ZERO) { sum, (_, len) -> sum + BigInteger.ONE.shiftLeft(bits - len) }

    @Test
    fun ipv4_coversTheInternetButNotTheLan() {
        val routes = LanRoutes.ipv4
        listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "11.0.0.1").forEach {
            assertTrue("$it should go into the tun", covered(routes, 32, it))
        }
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.254", "192.168.1.1", "169.254.1.1", "224.0.0.251", "255.255.255.255").forEach {
            assertFalse("$it should stay on the local network", covered(routes, 32, it))
        }
    }

    @Test
    fun ipv4_keepsTheVpnsOwnRangesInTheTun() {
        // hev's MapDNS fake addresses and the VPN's DNS server.
        assertTrue(covered(LanRoutes.ipv4, 32, "100.64.0.1"))
        assertTrue(covered(LanRoutes.ipv4, 32, "198.18.0.1"))
    }

    @Test
    fun ipv4_routesDontOverlap() {
        // All of IPv4 minus the excluded blocks: any overlap would make the sum larger.
        val excluded = listOf(8, 12, 16, 16, 3).fold(BigInteger.ZERO) { sum, len -> sum + BigInteger.ONE.shiftLeft(32 - len) }
        assertEquals(BigInteger.ONE.shiftLeft(32) - excluded, size(LanRoutes.ipv4, 32))
    }

    @Test
    fun ipv6_coversTheInternetButNotTheLan() {
        val routes = LanRoutes.ipv6
        assertTrue(covered(routes, 128, "2001:4860:4860::8888"))
        listOf("fd00::1", "fc00::1", "fe80::1", "ff02::1").forEach {
            assertFalse("$it should stay on the local network", covered(routes, 128, it))
        }
        val excluded = listOf(7, 10, 8).fold(BigInteger.ZERO) { sum, len -> sum + BigInteger.ONE.shiftLeft(128 - len) }
        assertEquals(BigInteger.ONE.shiftLeft(128) - excluded, size(routes, 128))
    }
}
