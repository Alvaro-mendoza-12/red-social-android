package conectate.com.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class BackendTrustPolicyTest {
    @Test
    fun `canonical backend accepts only root origins`() {
        assertEquals(
            "https://api.novvin.example",
            BackendTrustPolicy.canonicalBaseUrl("https://API.Novvin.Example/")
        )
        assertNull(BackendTrustPolicy.canonicalBaseUrl("https://api.novvin.example/path"))
        assertNull(BackendTrustPolicy.canonicalBaseUrl("https://user:pass@api.novvin.example"))
        assertNull(BackendTrustPolicy.canonicalBaseUrl("http://api.novvin.example"))
        assertEquals(
            "https://conectate.tail9b267c.ts.net:8443",
            BackendTrustPolicy.canonicalBaseUrl("https://Conectate.Tail9B267C.Ts.Net:8443/")
        )
        assertNull(BackendTrustPolicy.canonicalBaseUrl("https://api.novvin.example:8443"))
        assertTrue(
            BackendTrustPolicy.isExplicitlyTrusted(
                "https://api.novvin.example/",
                "https://api.novvin.example"
            )
        )
        assertFalse(
            BackendTrustPolicy.isExplicitlyTrusted(
                "https://attacker.example",
                "https://api.novvin.example"
            )
        )
    }

    @Test
    fun `valid signature authorizes a bounded dynamic endpoint`() {
        val fixture = signedFixture(ttlSeconds = 600)
        val verified = BackendTrustPolicy.verifyDiscoveryEnvelope(
            fixture.envelope,
            fixture.publicKeyBase64,
            fixture.nowEpochSeconds
        )
        requireNotNull(verified)
        assertEquals("https://dynamic-api.novvin.example", verified.url)
        assertEquals(fixture.nowEpochSeconds + 600, verified.expiresAtEpochSeconds)
    }

    @Test
    fun `tampered expired and excessive envelopes fail closed`() {
        val fixture = signedFixture(ttlSeconds = 600)
        val parts = fixture.envelope.split(':').toMutableList()
        parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "https://attacker.example".toByteArray(StandardCharsets.UTF_8)
        )
        assertNull(
            BackendTrustPolicy.verifyDiscoveryEnvelope(
                parts.joinToString(":"),
                fixture.publicKeyBase64,
                fixture.nowEpochSeconds
            )
        )
        assertNull(
            BackendTrustPolicy.verifyDiscoveryEnvelope(
                fixture.envelope,
                fixture.publicKeyBase64,
                fixture.nowEpochSeconds + 1_000
            )
        )

        val excessive = signedFixture(ttlSeconds = 8 * 24 * 60 * 60)
        assertNull(
            BackendTrustPolicy.verifyDiscoveryEnvelope(
                excessive.envelope,
                excessive.publicKeyBase64,
                excessive.nowEpochSeconds
            )
        )
    }

    private fun signedFixture(ttlSeconds: Int): Fixture {
        val keyPair = generateKeyPair()
        val now = 2_000_000_000L
        val expiresAt = now + ttlSeconds
        val url = "https://dynamic-api.novvin.example"
        val input = "novvin1\n$expiresAt\n$url".toByteArray(StandardCharsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(input)
        val signature = signer.sign()
        val envelope = listOf(
            "novvin1",
            expiresAt.toString(),
            Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(StandardCharsets.UTF_8)),
            Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        ).joinToString(":")
        return Fixture(
            envelope = envelope,
            publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            nowEpochSeconds = now
        )
    }

    private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private data class Fixture(
        val envelope: String,
        val publicKeyBase64: String,
        val nowEpochSeconds: Long
    )
}
