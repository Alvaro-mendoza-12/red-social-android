package conectate.com.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocializationModelsTest {
    @Test
    fun `consent patch changes exactly one explicit preference`() {
        val patch = SocializationPreferencePatch.forKey(
            SocializationPreferenceKey.COMMUNITY_DISCOVERY,
            enabled = true,
        )

        assertEquals(true, patch.communityDiscoveryOptIn)
        assertEquals(null, patch.discoveryOptIn)
        assertEquals(null, patch.connectionRequestsOptIn)
        assertEquals(null, patch.icebreakersOptIn)
        assertEquals(null, patch.socialRoomsOptIn)
    }

    @Test
    fun `feature availability is separate from a stored opt in`() {
        val preferences = SocializationPreferences(discoveryOptIn = true)
        val capabilities = SocializationCapabilities(
            enabled = true,
            features = SocializationFeatureCapabilities(discovery = false),
            preferences = preferences,
        )

        assertTrue(preferences.isEnabled(SocializationPreferenceKey.DISCOVERY))
        assertFalse(capabilities.features.isEnabled(SocializationPreferenceKey.DISCOVERY))
        assertFalse(capabilities.canEdit(SocializationPreferenceKey.DISCOVERY))
    }
}
