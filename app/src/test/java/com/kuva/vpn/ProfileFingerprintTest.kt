package com.kuva.vpn

import com.kuva.vpn.domain.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileFingerprintTest {

    @Test
    fun `same server different uuid has different fingerprint`() {
        val a = profile(uuid = "uuid-1")
        val b = profile(uuid = "uuid-2")
        assertNotEquals(Profile.fingerprintOf(a), Profile.fingerprintOf(b))
    }

    @Test
    fun `identical configs fingerprint equal regardless of name`() {
        val a = profile(uuid = "u").copy(name = "Name A")
        val b = profile(uuid = "u").copy(name = "Name B")
        assertEquals(Profile.fingerprintOf(a), Profile.fingerprintOf(b))
    }

    @Test
    fun `case-insensitive host match`() {
        val a = profile(uuid = "u").copy(address = "Example.COM")
        val b = profile(uuid = "u").copy(address = "example.com")
        assertEquals(Profile.fingerprintOf(a), Profile.fingerprintOf(b))
    }

    private fun profile(uuid: String) = Profile(
        scope = Profile.Scope.FREE, name = "x",
        protocol = Profile.Protocol.VLESS, address = "example.com", port = 443,
        uuid = uuid, network = Profile.Transport.TCP, security = Profile.Security.TLS,
    )
}
