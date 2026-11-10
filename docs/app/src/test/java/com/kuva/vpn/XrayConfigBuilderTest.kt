package com.kuva.vpn

import com.kuva.vpn.core.config.XrayConfigBuilder
import com.kuva.vpn.domain.model.Profile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderTest {

    private val vlessReality = Profile(
        scope = Profile.Scope.FREE,
        name = "test-reality",
        protocol = Profile.Protocol.VLESS,
        address = "example.com",
        port = 443,
        uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
        flow = "xtls-rprx-vision",
        network = Profile.Transport.TCP,
        security = Profile.Security.REALITY,
        sni = "www.microsoft.com",
        fingerprint = "chrome",
        publicKey = "pbk-test",
        shortId = "abcd",
    )

    @Test
    fun `reality config has correct stream settings`() {
        val json = JSONObject(XrayConfigBuilder.build(vlessReality, opts()))
        val ob = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", ob.getString("protocol"))
        val stream = ob.getJSONObject("streamSettings")
        assertEquals("reality", stream.getString("security"))
        val reality = stream.getJSONObject("realitySettings")
        assertEquals("pbk-test", reality.getString("publicKey"))
        assertEquals("abcd", reality.getString("shortId"))
    }

    @Test
    fun `config contains socks inbound on expected port`() {
        val json = JSONObject(XrayConfigBuilder.build(vlessReality, opts()))
        val inbound = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(10808, inbound.getInt("port"))
        assertEquals("127.0.0.1", inbound.getString("listen"))
    }

    @Test
    fun `dns servers written`() {
        val json = JSONObject(XrayConfigBuilder.build(vlessReality, opts()))
        val dns = json.getJSONObject("dns").getJSONArray("servers")
        assertEquals("1.1.1.1", dns.getString(0))
    }

    @Test
    fun `vmess config parses`() {
        val vmess = Profile(
            scope = Profile.Scope.PERSONAL,
            name = "v", protocol = Profile.Protocol.VMESS,
            address = "1.2.3.4", port = 100, uuid = "uid",
            network = Profile.Transport.WS, security = Profile.Security.TLS,
            path = "/path", host = "cdn.example.com",
        )
        val json = JSONObject(XrayConfigBuilder.build(vmess, opts()))
        val ob = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vmess", ob.getString("protocol"))
        assertEquals("ws", ob.getJSONObject("streamSettings").getString("network"))
        assertEquals("/path", ob.getJSONObject("streamSettings").getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun `trojan and shadowsocks build`() {
        val trojan = Profile(scope = Profile.Scope.FREE, name = "t",
            protocol = Profile.Protocol.TROJAN, address = "a", port = 443,
            uuid = "pass", security = Profile.Security.TLS, sni = "sni.example.com")
        val j1 = JSONObject(XrayConfigBuilder.buildTestConfig(trojan, 10809))
        assertEquals("trojan", j1.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))

        val ss = Profile(scope = Profile.Scope.FREE, name = "s",
            protocol = Profile.Protocol.SHADOWSOCKS, address = "a", port = 8388,
            uuid = "secret", method = "aes-256-gcm")
        val j2 = JSONObject(XrayConfigBuilder.buildTestConfig(ss, 10809))
        assertEquals("shadowsocks", j2.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
    }

    private fun opts() = XrayConfigBuilder.Options(
        socksPort = 10808, httpPort = 10809,
        dnsServers = listOf("1.1.1.1"), bypassLan = true,
    )
}
