package conectate.com.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlgorithmModelsTest {
    @Test
    fun `creator and topic rules remain independent for the dolphin case`() {
        val creator = AlgorithmPreference(
            surface = AlgorithmSurface.VIDEO.wireName,
            dimension = AlgorithmDimension.CREATOR.wireName,
            subjectKey = "carlos",
            score = 90,
        )
        val dolphins = AlgorithmPreference(
            surface = AlgorithmSurface.VIDEO.wireName,
            dimension = AlgorithmDimension.TOPIC.wireName,
            subjectKey = "delfines",
            score = -100,
        )

        assertEquals(90, creator.score)
        assertTrue(dolphins.isBlocked)
        assertEquals(AlgorithmDimension.CREATOR, AlgorithmDimension.fromWire(creator.dimension))
        assertEquals(AlgorithmDimension.TOPIC, AlgorithmDimension.fromWire(dolphins.dimension))
    }

    @Test
    fun `feed mix is valid only when it totals exactly one hundred`() {
        val valid = AlgorithmFeedMix(
            following = 40,
            interests = 30,
            communities = 10,
            newContent = 10,
            trends = 5,
            exploration = 5,
        )
        val invalid = valid.copy(exploration = 6)

        assertTrue(valid.isValid())
        assertFalse(invalid.isValid())
    }

    @Test
    fun `behavioral permissions start disabled and never include private messages`() {
        val permissions = AlgorithmDataPermissions()

        assertFalse(permissions.likes)
        assertFalse(permissions.watchTime)
        assertFalse(permissions.profileVisits)
        assertFalse(permissions.publicInteractions)
    }

    @Test
    fun `capabilities decode from the server nested flags`() {
        val capabilities = AlgorithmCapabilities(
            enabled = true,
            capabilities = AlgorithmCapabilityFlags(preferences = true, feedMix = true),
        )

        assertTrue(capabilities.enabled)
        assertTrue(capabilities.preferencesEnabled)
        assertTrue(capabilities.feedMixEnabled)
        assertFalse(capabilities.dataPermissionsEnabled)
    }
}
