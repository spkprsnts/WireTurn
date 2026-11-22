package com.wireturn.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingProfileTest {

    // Happ's own example (the RoscomVPN profile), as in its and INCY's docs.
    private val json = """
        {
            "Name": "RoscomVPN",
            "GlobalProxy": "true",
            "RemoteDNSType": "DoH",
            "RemoteDNSDomain": "https://cloudflare-dns.com/dns-query",
            "RemoteDNSIP": "1.1.1.1",
            "DomesticDNSType": "DoH",
            "DomesticDNSDomain": "https://dns.google/dns-query",
            "DomesticDNSIP": "8.8.8.8",
            "Geoipurl": "https://github.com/hydraponique/roscomvpn-geoip/releases/latest/download/geoip.dat",
            "Geositeurl": "https://github.com/hydraponique/roscomvpn-geosite/releases/latest/download/geosite.dat",
            "DnsHosts": { "cloudflare-dns.com": "1.1.1.1" },
            "DirectSites": ["geosite:ru", "domain:example.org"],
            "DirectIp": ["geoip:ru", "10.0.0.0/8"],
            "ProxySites": [],
            "ProxyIp": [],
            "BlockSites": ["geosite:category-ads-all"],
            "BlockIp": [],
            "DomainStrategy": "IPIfNonMatch",
            "FakeDNS": "false"
        }
    """.trimIndent()

    private fun base64(text: String, urlSafe: Boolean = false): String {
        val encoder = if (urlSafe) java.util.Base64.getUrlEncoder().withoutPadding() else java.util.Base64.getEncoder()
        return encoder.encodeToString(text.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun everyForm_givesTheSameProfile() {
        val forms = listOf(
            json,
            base64(json),
            "happ://routing/onadd/" + base64(json),
            "happ://routing/add/" + base64(json),
            "incy://routing/onadd/" + base64(json, urlSafe = true),
            "://routing/onadd/" + base64(json),
            "  happ://routing/onadd/" + base64(json) + "\n"
        )
        val parsed = forms.map { RoutingProfile.parse(it) }
        parsed.forEach { assertNotNull(it) }
        assertEquals(1, parsed.toSet().size)
    }

    @Test
    fun fields_mapOntoTheXraySettings() {
        val profile = RoutingProfile.parse(json)!!
        assertEquals("RoscomVPN", profile.name)
        assertEquals("geosite:ru,domain:example.org,geoip:ru,10.0.0.0/8", profile.routeDirect)
        assertEquals("geosite:category-ads-all", profile.routeBlock)
        assertEquals("1.1.1.1", profile.remoteDnsIp)
        assertEquals(false, profile.fakeDns)
        assertEquals(GeoResourceSet.ROSCOMVPN.geoipUrl, profile.geoipUrl)
        assertEquals(GeoResourceSet.ROSCOMVPN.geositeUrl, profile.geositeUrl)
        assertEquals(
            listOf("RemoteDNSDomain (DoH)", "DomesticDNS", "DnsHosts", "DomainStrategy: IPIfNonMatch"),
            profile.unsupported
        )
    }

    @Test
    fun proxyOnlyProfile_isFlagged() {
        val profile = RoutingProfile.parse(
            """{"Name":"Only listed","GlobalProxy":"false","ProxySites":["geosite:youtube"],"ProxyIp":"1.2.3.0/24"}"""
        )!!
        assertTrue("GlobalProxy: false" in profile.unsupported)
        assertTrue("ProxySites" in profile.unsupported)
        assertTrue("ProxyIp" in profile.unsupported)
        assertEquals("", profile.routeDirect)
    }

    @Test
    fun keys_ignoreCase_andListsMayBeStrings() {
        val profile = RoutingProfile.parse(
            """{"name":"x","directsites":"geosite:ru, geoip:ru ,","fakedns":true,"geoipurl":"https://a/geoip.dat"}"""
        )!!
        assertEquals("geosite:ru,geoip:ru", profile.routeDirect)
        assertEquals(true, profile.fakeDns)
        // One geo file alone isn't a source.
        assertNull(profile.geoipUrl)
        assertNull(profile.geositeUrl)
    }

    @Test
    fun notAProfile_isNull() {
        assertNull(RoutingProfile.parse(""))
        assertNull(RoutingProfile.parse("hello"))
        assertNull(RoutingProfile.parse("""{"outbounds":[]}"""))
        assertNull(RoutingProfile.parse("happ://routing/off"))
        // A link to the profile, not the profile.
        assertNull(RoutingProfile.parse("incy://autorouting/onadd/https://example.com/routing.json"))
        assertNull(RoutingProfile.parse("vless://uuid@host:443#name"))
    }
}
