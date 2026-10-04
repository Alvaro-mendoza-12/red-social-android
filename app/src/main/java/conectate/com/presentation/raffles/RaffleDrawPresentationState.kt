package conectate.com.presentation.raffles

import conectate.com.data.raffles.RaffleResult
import conectate.com.data.raffles.RaffleRound

/** Presentation only. Winners and their order always come from the stored server round. */
internal data class RaffleDrawPresentationState(
    val roundId: String? = null,
    val observedPosition: Int = 0,
    val revealedPosition: Int = 0,
    val pending: List<RaffleResult> = emptyList(),
) {
    val active: RaffleResult? get() = pending.firstOrNull()

    fun receive(round: RaffleRound?): RaffleDrawPresentationState {
        if (round == null) return RaffleDrawPresentationState()
        val ordered = round.results.distinctBy { it.position }.sortedBy { it.position }
        val last = ordered.lastOrNull()?.position ?: 0
        if (roundId != round.id) {
            // Opening a finished history never replays its draws. Opening a live
            // round presents its latest draw; a newly observed round queues all draws.
            val baseline = if (roundId == null) {
                if (round.status == "running" && ordered.isNotEmpty()) ordered.dropLast(1).lastOrNull()?.position ?: 0
                else last
            } else 0
            return RaffleDrawPresentationState(round.id, last, baseline, ordered.filter { it.position > baseline })
        }
        return copy(observedPosition = maxOf(observedPosition, last),
            pending = pending + ordered.filter { it.position > observedPosition })
    }

    fun reveal(position: Int): RaffleDrawPresentationState =
        if (active?.position == position) copy(revealedPosition = maxOf(revealedPosition, position)) else this

    fun finish(position: Int): RaffleDrawPresentationState =
        if (active?.position == position) copy(revealedPosition = maxOf(revealedPosition, position), pending = pending.drop(1)) else this
}
