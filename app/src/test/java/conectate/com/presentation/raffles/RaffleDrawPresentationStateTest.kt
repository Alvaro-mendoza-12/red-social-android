package conectate.com.presentation.raffles

import conectate.com.data.raffles.*
import org.junit.Assert.*
import org.junit.Test

class RaffleDrawPresentationStateTest {
    private val people = List(5) { RaffleParticipant("p$it", name = "Persona $it") }
    private fun round(count: Int = 0, id: String = "r1", status: String = "running") =
        RaffleRound(id, 1, status, people, List(count) { RaffleResult(it + 1, people[it], "now") }, "qa", startedAt = "now")

    @Test fun firstDrawOfObservedRoundIsQueued() {
        val state = RaffleDrawPresentationState().receive(round()).receive(round(1))
        assertEquals(people[0], state.active!!.participant)
        assertEquals(0, state.revealedPosition)
    }

    @Test fun liveRoundAlreadyContainingItsFirstWinnerAnimatesIt() {
        val state = RaffleDrawPresentationState().receive(round(1))
        assertEquals(1, state.pending.size)
        assertEquals(0, state.revealedPosition)
    }

    @Test fun multipleResultsArrivingTogetherKeepServerOrder() {
        var state = RaffleDrawPresentationState().receive(round()).receive(round(3))
        assertEquals(listOf(1, 2, 3), state.pending.map { it.position })
        state = state.reveal(1).finish(1)
        assertEquals(1, state.revealedPosition)
        assertEquals(2, state.active!!.position)
    }

    @Test fun refreshDoesNotCancelTheActiveDrawOrDuplicateQueuedResults() {
        val state = RaffleDrawPresentationState().receive(round()).receive(round(1))
        val refreshed = state.receive(round(2)).receive(round(2))
        assertEquals(1, refreshed.active!!.position)
        assertEquals(listOf(1, 2), refreshed.pending.map { it.position })
        assertEquals(state, state.receive(round(0)))
    }

    @Test fun newRoundAllowsTheSamePersonToWinAgain() {
        val state = RaffleDrawPresentationState().receive(round()).receive(round(1)).finish(1)
        val next = state.receive(round(1, "r2"))
        assertEquals("r2", next.roundId)
        assertEquals(people[0], next.active!!.participant)
        assertEquals(0, next.revealedPosition)
        assertEquals(next, next.finish(4))
    }

    @Test fun openingFinishedHistoryDoesNotReplayStoredDraws() {
        val state = RaffleDrawPresentationState().receive(round(5, status = "completed"))
        assertNull(state.active)
        assertEquals(5, state.revealedPosition)
    }

    @Test fun lastLiveDrawStillAnimatesWhenServerCompletesRound() {
        var state = RaffleDrawPresentationState().receive(round(4))
        state = state.finish(4).receive(round(5, status = "completed"))
        assertEquals(5, state.active!!.position)
        assertEquals(4, state.revealedPosition)
    }
}
