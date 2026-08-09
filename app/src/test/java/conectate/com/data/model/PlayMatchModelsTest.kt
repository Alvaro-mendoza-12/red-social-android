package conectate.com.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayMatchModelsTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `decodes server driven catalog and public player contract`() {
        val catalog = json.decodeFromString<PlayMatchCatalogResponse>(
            """{"items":[{"id":"valorant","name":"Valorant","platforms":["PC"],"ranks":["gold"],"roles":["support"],"crossPlay":false}]}""",
        )
        assertEquals("PC", catalog.items.single().platforms.single())

        val page = json.decodeFromString<PlayMatchPlayersResponse>(
            """{"items":[{"userId":"public-1","displayName":"Ana","games":[],"compatibilityScore":82,"compatibilityReasons":[{"code":"COMMON_GAME","points":30,"label":"Juego en común"}]}],"safeguards":{"preciseLocationUsed":false,"privateMessagesUsed":false,"ageOrGenderUsed":false}}""",
        )
        assertEquals(82, page.items.single().compatibilityScore)
        assertFalse(page.safeguards.preciseLocationUsed)
        assertFalse(page.safeguards.privateMessagesUsed)
        assertFalse(page.safeguards.ageOrGenderUsed)
    }

    @Test
    fun `decodes snake case preference row from PostgreSQL`() {
        val response = json.decodeFromString<PlayMatchProfileResponse>(
            """{"profile":{"userId":"me"},"preferences":{"game_ids":["valorant"],"rank_tolerance":3,"voice_required":true,"discovery_level":40}}""",
        )
        assertEquals(listOf("valorant"), response.preferences?.gameIds)
        assertEquals(3, response.preferences?.rankTolerance)
        assertEquals(true, response.preferences?.voiceRequired)
    }
}

