package conectate.com.data.algorithm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class AlgorithmApiPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `capabilities response keeps enabled and nested feature flags`() {
        val decoded = decodeAlgorithmCapabilitiesPayload(
            json,
            """{"enabled":true,"capabilities":{"classification":true,"preferences":true,"feedMix":true,"dataPermissions":true,"explanations":true}}""",
        )

        assertTrue(decoded.enabled)
        assertTrue(decoded.classificationEnabled)
        assertTrue(decoded.preferencesEnabled)
        assertTrue(decoded.feedMixEnabled)
        assertTrue(decoded.dataPermissionsEnabled)
        assertTrue(decoded.explanationsEnabled)
    }
}
