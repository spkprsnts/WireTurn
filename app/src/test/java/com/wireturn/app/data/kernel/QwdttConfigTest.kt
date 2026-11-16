package com.wireturn.app.data.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QwdttConfigTest {

    private fun parse(link: String): QwdttConfig = QwdttConfig.parse(link) ?: fail("not parsed: $link") as Nothing

    @Test
    fun qwdttLink() {
        val config = parse("qwdtt://config?peer=1.2.3.4:56000&hashes=a,b&workers=18&pass=pw&dk=key")
        assertEquals("1.2.3.4:56000", config.peer)
        assertEquals("a,b", config.vkHashes)
        assertEquals(18, config.workers)
        assertEquals("pw", config.password)
        assertEquals("key", config.diagKey)
        // A link is a new profile: this device's id.
        assertEquals(QwdttConfig.THIS_DEVICE, config.deviceId)
    }

    @Test
    fun schemelessLink() {
        assertEquals("h:1", parse("qwdtt:config?peer=h:1&hashes=a&pass=p").peer)
    }

    @Test
    fun peerPort_explicitWinsThenParamThenDefault() {
        assertEquals("h:9", parse("qwdtt://config?peer=h:9&dtls_port=1&hashes=a").peer)
        assertEquals("h:1234", parse("qwdtt://config?peer=h&dtls_port=1234&hashes=a").peer)
        assertEquals("h:1234", parse("qwdtt://config?peer=h&server_port=1234&hashes=a").peer)
        assertEquals("h:56000", parse("qwdtt://config?peer=h&hashes=a").peer)
    }

    @Test
    fun withoutPeerOrHashes_isNotALink() {
        assertNull(QwdttConfig.parse("qwdtt://config?peer=h:1"))
        assertNull(QwdttConfig.parse("qwdtt://config?hashes=a"))
        assertNull(QwdttConfig.parse("https://example.com"))
    }

    @Test
    fun legacyPositionalWdtt() {
        val config = parse("wdtt://1.2.3.4:56000:51820:9000:pw:hash")
        assertEquals("1.2.3.4:56000", config.peer)
        assertEquals("pw", config.password)
        assertEquals("hash", config.vkHashes)
    }

    @Test
    fun wdttPlusConnect() {
        val config = parse("wdtt://connect?v=1&host=h&dtls=56000&wg=51820&local=9000&password=p&hashes=x&max_workers=20")
        assertEquals("h:56000", config.peer)
        assertEquals("p", config.password)
        assertEquals(20, config.workers)
        assertNull(QwdttConfig.parse("wdtt://connect?v=2&host=h&dtls=1&password=p&hashes=x"))
    }

    @Test
    fun toUri_roundTrips() {
        val original = QwdttConfig(peer = "h:1", vkHashes = "a,b", password = "p w", workers = 27, diagKey = "k")
        val back = parse(original.toUri("Name"))
        assertEquals(original.peer, back.peer)
        assertEquals(original.vkHashes, back.vkHashes)
        assertEquals(original.password, back.password)
        assertEquals(original.workers, back.workers)
        assertEquals(original.diagKey, back.diagKey)
    }

    @Test
    fun resolvedDeviceId() {
        // A profile from before the field: the "unknown" WireTurn always sent.
        assertEquals("unknown", QwdttConfig(deviceId = null).resolvedDeviceId("aid"))
        assertEquals("aid", QwdttConfig(deviceId = "").resolvedDeviceId("aid"))
        assertEquals("unknown", QwdttConfig(deviceId = "").resolvedDeviceId(""))
        assertEquals("custom", QwdttConfig(deviceId = "custom").resolvedDeviceId("aid"))
    }

    @Test
    fun sanitize_capsWorkersAtTheBinarysLimit() {
        assertEquals(QwdttConfig.MAX_WORKERS, QwdttConfig(workers = 500).sanitize().workers)
        assertEquals(1, QwdttConfig(workers = 0).sanitize().workers)
        assertEquals(162, QwdttConfig(workers = 162).sanitize().workers)
    }

    @Test
    fun sanitize_keepsALegacyDeviceIdNull() {
        assertNull(QwdttConfig(deviceId = null).sanitize().deviceId)
        assertEquals("x", QwdttConfig(deviceId = "  x ").sanitize().deviceId)
    }
}
