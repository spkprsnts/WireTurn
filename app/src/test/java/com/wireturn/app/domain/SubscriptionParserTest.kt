package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.ProfileBundle
import com.wireturn.app.data.SafeEnumTypeAdapterFactory
import com.wireturn.app.data.kernel.QwdttConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubscriptionParserTest {

    // The same Gson ProfileManager hands the parser.
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter())
        .create()
    private val parser = SubscriptionParser(gson)

    private fun parsed(content: String): ProfileBundle =
        when (val result = parser.parse(content)) {
            is SubscriptionParser.Result.Parsed -> result.bundle
            else -> fail("expected a parsed bundle, got $result") as Nothing
        }

    private fun qwdtt(profile: Profile): QwdttConfig = (profile.kernelConfig as KernelConfig.Qwdtt).config

    private fun base64(text: String): String =
        java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    // --- qWDTT's own subscription JSON ---

    @Test
    fun qwdttObject_mapsFieldsAsTheOfficialClient() {
        val bundle = parsed(
            """
            {"subscriptionName": "Group", "description": "About", "trafficUsedMb": 1.5, "trafficLimitMb": 100,
             "profiles": [{"name": "A", "peer": "1.2.3.4", "hashes": "h1,h2", "pass": "pw",
                           "workers": 12, "workersPerHash": 5, "dk": "key"}]}
            """
        )
        assertEquals("Group", bundle.name)
        assertEquals("About", bundle.description)
        assertEquals((1.5 * 1024 * 1024).toLong(), bundle.bytesUsed)
        assertEquals(100L * 1024 * 1024, bundle.bytesTotal)

        val profile = bundle.profiles.single()
        assertEquals("A", profile.name)
        val config = qwdtt(profile)
        // A bare host gets the standard DTLS port, as the official client's ensurePort does.
        assertEquals("1.2.3.4:56000", config.peer)
        assertEquals("h1,h2", config.vkHashes)
        assertEquals("pw", config.password)
        // "workers" wins over "workersPerHash", as in the official client.
        assertEquals(12, config.workers)
        assertEquals("key", config.diagKey)
        // A new profile: this device's id, not the legacy "unknown".
        assertEquals(QwdttConfig.THIS_DEVICE, config.deviceId)
    }

    @Test
    fun qwdttServersKeyAndAliases() {
        val bundle = parsed(
            """{"groupName": "G", "info": "i", "servers": [{"peer": "h:1", "vkHashes": "x", "password": "p", "workersPerHash": 7}]}"""
        )
        assertEquals("G", bundle.name)
        assertEquals("i", bundle.description)
        val config = qwdtt(bundle.profiles.single())
        assertEquals("h:1", config.peer)
        assertEquals("x", config.vkHashes)
        assertEquals(7, config.workers)
    }

    @Test
    fun qwdttBareArray_takesTheGroupNameFromItsFirstEntry() {
        val bundle = parsed("""[{"groupName": "Arr", "peer": "h:1", "hashes": "x", "pass": "p"}]""")
        assertEquals("Arr", bundle.name)
        assertEquals(1, bundle.profiles.size)
    }

    @Test
    fun qwdttBase64Wrapped() {
        val bundle = parsed(base64("""{"profiles": [{"peer": "h:1", "hashes": "x", "pass": "p"}]}"""))
        assertEquals("h:1", qwdtt(bundle.profiles.single()).peer)
    }

    @Test
    fun qwdttMalformedNumber_fallsBackToTheDefault() {
        val bundle = parsed("""{"version": "v2", "profiles": [{"peer": "h:1", "hashes": "x", "pass": "p", "workers": "many"}]}""")
        assertEquals(QwdttConfig().workers, qwdtt(bundle.profiles.single()).workers)
    }

    @Test
    fun qwdttWithoutHashes_isUnusable() {
        assertEquals(
            SubscriptionParser.Result.QwdttUnusable,
            parser.parse("""{"profiles": [{"peer": "h:1", "pass": "p", "workers": 9}]}""")
        )
    }

    @Test
    fun qwdttIds_areStableAcrossRefreshes() {
        val content = """{"profiles": [{"peer": "h:1", "hashes": "x", "pass": "p"}, {"peer": "h:2", "hashes": "x", "pass": "p"}]}"""
        val first = parsed(content).profiles.map { it.id }
        val second = parsed(content).profiles.map { it.id }
        assertEquals(first, second)
        assertNotEquals(first[0], first[1])
    }

    @Test
    fun qwdttId_isFromThePeerAsSent_notWithTheDefaultPort() {
        // Profiles imported before the default port was added got this id; another one would make
        // a refresh replace them, their device id ("unknown") with it.
        val profile = parsed("""{"profiles": [{"peer": "vpn.example.com", "hashes": "H", "pass": "p"}]}""").profiles.single()
        assertEquals("vpn.example.com:56000", qwdtt(profile).peer)
        assertEquals(
            java.util.UUID.nameUUIDFromBytes("qwdtt-sub:vpn.example.com:H".toByteArray(Charsets.UTF_8)).toString(),
            profile.id
        )
    }

    // --- other formats ---

    @Test
    fun wireTurnBundle_isNotTakenForQwdtt() {
        val original = ProfileBundle(
            name = "Mine",
            profiles = listOf(
                Profile(id = "p1", name = "P", kernelConfig = KernelConfig.Qwdtt(QwdttConfig(peer = "h:1", vkHashes = "x", password = "y")))
            )
        )
        val bundle = parsed(gson.toJson(original))
        assertEquals("Mine", bundle.name)
        val profile = bundle.profiles.single()
        // Its own id, not one derived from the entry the way qWDTT's are.
        assertEquals("p1", profile.id)
        assertEquals("h:1", qwdtt(profile).peer)
    }

    @Test
    fun textSubscription_withQwdttLinks() {
        val bundle = parsed("qwdtt://config?peer=1.2.3.4:5000&hashes=abc&pass=p&name=Srv\n")
        val config = qwdtt(bundle.profiles.single())
        assertEquals("1.2.3.4:5000", config.peer)
        assertEquals("abc", config.vkHashes)
        // And the same id on the next refresh of the same line.
        assertEquals(
            bundle.profiles.single().id,
            parsed("qwdtt://config?peer=1.2.3.4:5000&hashes=abc&pass=p&name=Srv\n").profiles.single().id
        )
    }

    @Test
    fun base64TextSubscription() {
        val bundle = parsed(base64("qwdtt://config?peer=h:1&hashes=abc&pass=p\n"))
        assertEquals("h:1", qwdtt(bundle.profiles.single()).peer)
    }

    // A Happ/INCY-style body: the panel's headers as "#key: value" lines before the links.
    private val panelStyleBody = listOf(
        "#profile-title: base64:" + base64("Мой VPN"),
        "#profile-description: Описание",
        "#announce: base64:" + base64("Строка 1\nСтрока 2"),
        "#support-url: https://t.me/support",
        "#profile-web-page-url: javascript:alert(1)",
        "#profile-update-interval: 12",
        "#subscription-userinfo: upload=10; download=20; total=100; expire=1767225600",
        "#subscription-refill-date: 1795392300",
        "vless://00000000-0000-4000-8000-000000000001@example.com:443?encryption=none&security=tls&type=tcp#Server"
    ).joinToString("\n")

    @Test
    fun panelStyleBodyLines_fillTheBundle() {
        for (content in listOf(panelStyleBody, base64(panelStyleBody))) {
            val bundle = parsed(content)
            assertEquals("Мой VPN", bundle.name)
            assertEquals("Описание", bundle.description)
            assertEquals("Строка 1\nСтрока 2", bundle.announce)
            assertEquals("https://t.me/support", bundle.supportUrl)
            // Not a link the app opens - dropped, like the header.
            assertNull(bundle.webPageUrl)
            assertEquals(12 * 60, bundle.updateIntervalMinutes)
            assertEquals(30L, bundle.bytesUsed)
            assertEquals(100L, bundle.bytesTotal)
            assertEquals(1_767_225_600_000L, bundle.expiresAt)
            assertEquals(1_795_392_300_000L, bundle.refillAt)
            assertEquals("Server", bundle.profiles.single().name)
        }
    }

    @Test
    fun garbage_isUnrecognized() {
        assertEquals(SubscriptionParser.Result.Unrecognized, parser.parse("hello, this is not a subscription"))
    }

    // --- HTTP header and URL helpers ---

    @Test
    fun subscriptionUserinfo() {
        assertEquals(30L to 100L, parser.parseSubscriptionUserinfo("upload=10; download=20; total=100; expire=0"))
        assertEquals(null to 100L, parser.parseSubscriptionUserinfo("total=100"))
        assertEquals(null to null, parser.parseSubscriptionUserinfo(null))
    }

    @Test
    fun refillDate_isMillisOrNothing() {
        assertEquals(1_795_392_300_000L, parser.parseRefillDate("1795392300"))
        assertEquals(null, parser.parseRefillDate("0"))
        assertEquals(null, parser.parseRefillDate("soon"))
        assertEquals(null, parser.parseRefillDate(null))
    }

    @Test
    fun linkHeader_keepsWebAndTelegramLinksOnly() {
        assertEquals("https://t.me/support", parser.parseLinkHeader(" https://t.me/support "))
        assertEquals("tg://resolve?domain=support", parser.parseLinkHeader("tg://resolve?domain=support"))
        assertEquals(null, parser.parseLinkHeader("javascript:alert(1)"))
        assertEquals(null, parser.parseLinkHeader("intent://x#Intent;end"))
        assertEquals(null, parser.parseLinkHeader(""))
        assertEquals(null, parser.parseLinkHeader(null))
    }

    @Test
    fun subscriptionUserinfoExpire_isMillisAndZeroMeansNever() {
        assertEquals(1_767_225_600_000L, parser.parseSubscriptionExpire("upload=1; total=2; expire=1767225600"))
        assertEquals(null, parser.parseSubscriptionExpire("upload=10; download=20; total=100; expire=0"))
        assertEquals(null, parser.parseSubscriptionExpire("total=100"))
        assertEquals(null, parser.parseSubscriptionExpire(null))
        // Already millis (some panels send them).
        assertEquals(1_767_225_600_000L, parser.parseSubscriptionExpire("expire=1767225600000"))
    }

    @Test
    fun updateIntervalHeader_isHoursAsMinutes() {
        assertEquals(60, parser.parseUpdateIntervalHeader("1"))
        assertEquals(30, parser.parseUpdateIntervalHeader(" 0.5 "))
        assertNull(parser.parseUpdateIntervalHeader("0"))
        assertNull(parser.parseUpdateIntervalHeader("daily"))
        assertNull(parser.parseUpdateIntervalHeader(null))
    }

    @Test
    fun profileTitle_plainAndBase64() {
        assertEquals("Name", parser.decodeProfileTitle("Name"))
        assertEquals("Подписка", parser.decodeProfileTitle("base64:" + base64("Подписка")))
        assertNull(parser.decodeProfileTitle("  "))
        assertNull(parser.decodeProfileTitle(null))
    }

    @Test
    fun csqttPanelJsonUrl() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals("https://h/base/$uuid/json", parser.csqttPanelJsonUrl("https://h/base/$uuid/sub"))
        assertEquals("https://h/base/$uuid/json", parser.csqttPanelJsonUrl("https://h/base/$uuid/sub-group/"))
        assertNull(parser.csqttPanelJsonUrl("https://h/base/not-a-uuid/sub"))
        assertTrue(parser.csqttPanelJsonUrl("https://example.com/sub") == null)
    }
}
