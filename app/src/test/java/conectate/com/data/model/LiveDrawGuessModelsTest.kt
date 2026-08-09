package conectate.com.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDrawGuessModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `viewer state decodes without a private prompt`() {
        val state = json.decodeFromString<LiveDrawGuessState>(
            """
            {
              "game":{"id":"game-1","live_id":"live-1","status":"active"},
              "players":[{"user_id":"viewer","seat_index":1,"score":120,"rank":1,"status":"accepted"}],
              "round":{"id":"round-1","game_id":"game-1","round_number":1,"drawer_user_id":"drawer"},
              "private_prompt":null
            }
            """.trimIndent()
        )

        assertEquals("live-1", state.game.liveId)
        assertEquals("drawer", state.round?.drawerUserId)
        assertNull(state.privatePrompt)
    }

    @Test
    fun `drawer state decodes the server supplied private prompt`() {
        val state = json.decodeFromString<LiveDrawGuessState>(
            """
            {
              "game":{"id":"game-1"},
              "players":[],
              "round":{"id":"round-1","drawer_user_id":"drawer"},
              "private_prompt":{"key":"ballena","label":"Ballena","version":1}
            }
            """.trimIndent()
        )

        assertEquals("ballena", state.privatePrompt?.key)
        assertEquals("Ballena", state.privatePrompt?.label)
    }

    @Test
    fun `draw packet serialization contains only bounded protocol fields`() {
        val packet = LiveDrawPacket(
            sequence = 9L,
            points = listOf(LiveDrawPoint(x = 0.25f, y = 0.75f, deltaMs = 16))
        )
        val encoded = json.encodeToString(packet)

        assertTrue(encoded.contains("\"sequence\":9"))
        assertTrue(encoded.contains("\"points\""))
        assertFalse(encoded.contains("prompt"))
        assertFalse(encoded.contains("guess"))
    }
}
