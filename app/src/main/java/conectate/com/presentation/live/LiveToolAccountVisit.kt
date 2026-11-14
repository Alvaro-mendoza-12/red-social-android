package conectate.com.presentation.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.model.LiveComment
import conectate.com.data.supabase.AuthSessionLease

internal data class LiveToolVisitKey(val liveId: String, val accountId: String?, val generation: Long)

/** Retained UI callbacks must keep their original account generation, including A -> B -> A. */
internal class LiveToolAccountVisit(
    val key: LiveToolVisitKey,
    internal val owner: AuthSessionLease,
    private val commitOwner: (AuthSessionLease, () -> Unit) -> Boolean,
    private val entryCurrent: () -> Boolean = { true },
) {
    val isCurrent: Boolean get() = owner.isCurrent() && entryCurrent()
    fun run(action: () -> Unit): Boolean {
        var applied = false
        commitOwner(owner) { if (entryCurrent()) { action(); applied = true } }
        return applied
    }
}

internal fun captureLiveToolAccountVisit(
    liveId: String,
    captureOwner: () -> AuthSessionLease,
    currentGeneration: () -> Long,
    commitOwner: (AuthSessionLease, () -> Unit) -> Boolean,
    entryCurrent: () -> Boolean = { true },
): LiveToolAccountVisit {
    val owner = captureOwner()
    // An invalid capture must never reuse the key of a later valid capture.
    var generation = Long.MIN_VALUE
    commitOwner(owner) { generation = currentGeneration() }
    return LiveToolAccountVisit(LiveToolVisitKey(liveId, owner.accountId, generation), owner, commitOwner, entryCurrent)
}

@Composable
internal fun rememberLiveToolAccountVisit(liveId: String, viewModel: LiveSessionViewModel): LiveToolAccountVisit {
    val generation by viewModel.accountVisitGeneration.collectAsStateWithLifecycle()
    val account = viewModel.getCurrentUserId()
    return remember(liveId, account, generation) { viewModel.captureToolVisit(liveId) }
}

/** Visibility also rejects an invalid lease before a queued recomposition can replace this object. */
internal class LiveToolDialogState(private val visit: LiveToolAccountVisit) {
    private var game by mutableStateOf(false)
    private var points by mutableStateOf(false)
    private var comment by mutableStateOf<LiveComment?>(null)
    val gameVisible get() = visit.isCurrent && game
    val pointsVisible get() = visit.isCurrent && points
    val selectedComment get() = comment.takeIf { visit.isCurrent }
    fun openGame() { visit.run { game = true } }
    fun openPoints() { visit.run { points = true } }
    fun selectComment(value: LiveComment) { visit.run { comment = value } }
    fun closeGame() { game = false }
    fun closePoints() { points = false }
    fun clearComment() { comment = null }
    fun closeAll() { game = false; points = false; comment = null }
}

@Composable
internal fun rememberLiveToolDialogs(visit: LiveToolAccountVisit): LiveToolDialogState =
    remember(visit.key) { LiveToolDialogState(visit) }
