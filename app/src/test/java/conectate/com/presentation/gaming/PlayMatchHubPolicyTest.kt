package conectate.com.presentation.gaming

import conectate.com.data.model.PlayMatchConnection
import conectate.com.data.model.PlayMatchMatch
import conectate.com.data.model.PlayMatchProfile
import conectate.com.data.model.PlayMatchUserGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayMatchHubPolicyTest {
    @Test
    fun matchReadinessDistinguishesAnUnloadedProfileFromActionsTheUserCanTake() {
        assertEquals(PlayMatchStart.LOADING, playMatchStart(null, loading = true))
        assertEquals(PlayMatchStart.NO_PROFILE, playMatchStart(null, loading = false))
        val profile = PlayMatchProfile(userId = "me")
        assertEquals(PlayMatchStart.CONFIRM_AGE, playMatchStart(profile, loading = false))
        val adult = profile.copy(adultConfirmed = true)
        assertEquals(PlayMatchStart.ADD_GAME, playMatchStart(adult, loading = false))
        val withGame = adult.copy(games = listOf(PlayMatchUserGame(
            id = "my-game", gameId = "game", gameName = "Juego de prueba", platform = "PC",
        )))
        assertEquals(PlayMatchStart.ENABLE_DISCOVERY, playMatchStart(withGame, loading = false))
        assertEquals(PlayMatchStart.READY, playMatchStart(withGame.copy(discoverable = true), loading = false))
    }

    @Test
    fun activityCountsOnlyIncomingDecisionsAndOmitsChatsWithoutAnAddress() {
        val request = PlayMatchConnection("request", "PENDING", "INCOMING")
        val sent = PlayMatchConnection("sent", "PENDING", "OUTGOING")
        val chat = PlayMatchConnection("chat", "ACCEPTED", otherUserId = "player")
        val invalidChat = PlayMatchConnection("invalid", "ACCEPTED")
        val match = PlayMatchMatch("match")
        val projection = playMatchActivity(
            listOf(request, request, sent, chat, invalidChat),
            listOf(match, match, PlayMatchMatch("archived", status = "SKIPPED")),
        )
        assertEquals(listOf(request), projection.requests)
        assertEquals(listOf(sent), projection.sent)
        assertEquals(listOf(chat), projection.conversations)
        assertEquals(listOf(match), projection.decisions)
        assertEquals(2, projection.pending)
        assertFalse(projection.isEmpty)
    }
}
