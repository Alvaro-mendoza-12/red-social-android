package conectate.com.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDrawGuessRepositoryPolicyTest {
    @Test
    fun `only packets from the authoritative current game and round are accepted`() {
        assertTrue(liveDrawPacketMatchesState("game-1", "round-2", "game-1", "round-2"))
        assertFalse(liveDrawPacketMatchesState("game-1", "round-2", "game-old", "round-2"))
        assertFalse(liveDrawPacketMatchesState("game-1", "round-2", "game-1", "round-old"))
        assertFalse(liveDrawPacketMatchesState("game-1", null, "game-1", "round-2"))
        assertFalse(liveDrawPacketMatchesState("", "round-2", "", "round-2"))
    }
}
