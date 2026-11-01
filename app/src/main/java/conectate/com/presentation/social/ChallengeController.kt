package conectate.com.presentation.social

import conectate.com.core.concurrency.AccountTasks
import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.model.ActiveChallenge
import conectate.com.data.model.Challenge
import conectate.com.data.model.ChallengeActionException
import conectate.com.data.model.ChallengeSubmission
import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

data class ChallengeUiState(
    val ownerId: String? = null,
    val sessionEpoch: Long = 0,
    val active: ActiveChallenge? = null,
    val submissions: List<ChallengeSubmission> = emptyList(),
    val userChallenges: List<Challenge> = emptyList(),
    val hasParticipated: Boolean = false,
    val loadingRound: Boolean = false,
    val loadingSubmissions: Boolean = false,
    val submitting: Boolean = false,
    val mutating: Boolean = false,
    val pendingVotes: Set<String> = emptySet(),
    val remainingSeconds: Int = 0,
    val error: String? = null,
)

/** Captured when the editor or camera opens, rather than when its result arrives. */
class ChallengeEntry internal constructor(
    val challenge: ActiveChallenge,
    internal val sessionEpoch: Long,
    internal val lease: AuthSessionLease,
)

internal interface ChallengeDataSource {
    suspend fun round(owner: String): Result<ActiveChallenge>
    suspend fun submissions(owner: String, round: ActiveChallenge): Result<List<ChallengeSubmission>>
    suspend fun participated(owner: String, round: ActiveChallenge): Result<Boolean>
    suspend fun vote(owner: String, round: ActiveChallenge, id: String, desired: Boolean): Result<Pair<Int, Boolean>>
    suspend fun mine(owner: String): Result<List<Challenge>>
}

/** Owns all round state; late reads cannot overwrite an acknowledged action or another session. */
internal class ChallengeController(
    scope: CoroutineScope,
    private val captureLease: () -> AuthSessionLease,
    private val source: ChallengeDataSource,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val tasks = AccountTasks(scope)
    private var lease = captureLease()
    private var epoch = 0L
    private var visible = false
    private var visibleJob: Job? = null
    private var mineJob: Job? = null
    private var mineRevision = 0L
    private var participationRevision = 0L
    private var submissionsRevision = 0L
    private val mutableState = MutableStateFlow(ChallengeUiState(ownerId = lease.accountId))
    val state = mutableState.asStateFlow()

    fun sessionChanged() {
        if (lease.isCurrent()) return
        tasks.reset()
        visibleJob = null
        mineJob = null
        lease = captureLease()
        epoch++
        mineRevision++
        participationRevision++
        submissionsRevision++
        mutableState.value = ChallengeUiState(ownerId = lease.accountId, sessionEpoch = epoch)
        startVisibleLoop()
    }

    fun visibleState(snapshot: ChallengeUiState): ChallengeUiState =
        if (lease.isCurrent() && snapshot.sessionEpoch == epoch && snapshot.ownerId == lease.accountId) snapshot
        else ChallengeUiState()

    fun setVisible(value: Boolean) {
        visible = value
        sessionChanged()
        if (!value) { visibleJob?.cancel(); visibleJob = null }
        else startVisibleLoop()
    }

    private fun startVisibleLoop() {
        if (!visible || !lease.isCurrent() || visibleJob?.isActive == true) return
        visibleJob = tasks.scope.launch {
            refreshRound()
            var ticks = 0
            while (true) {
                delay(1_000)
                tick()
                ticks++
                if (ticks % 15 == 0 || (ticks % 5 == 0 && state.value.remainingSeconds == 0)) refreshRound()
                else if (ticks % 5 == 0 && canReadSubmissions()) refreshSubmissions()
            }
        }
    }

    fun refresh() {
        sessionChanged()
        if (visible && lease.isCurrent()) tasks.scope.launch { refreshRound() }
    }

    internal fun tick() {
        if (lease.isCurrent()) mutableState.value = state.value.copy(
            remainingSeconds = remainingSeconds(state.value.active, nowMillis()),
        )
    }

    private fun current(expectedEpoch: Long, expectedRound: ActiveChallenge? = null): Boolean =
        epoch == expectedEpoch && lease.isCurrent() &&
            (expectedRound == null || sameRound(state.value.active, expectedRound))

    private suspend fun refreshRound() {
        val owner = lease.accountId ?: return
        if (!lease.isCurrent() || state.value.loadingRound) return
        val expectedEpoch = epoch
        mutableState.value = state.value.copy(loadingRound = true)
        try {
            val result = runReadWithDeadline(10_000) { source.round(owner).getOrThrow() }
            currentCoroutineContext().ensureActive()
            if (!current(expectedEpoch)) return
            result.fold(onSuccess = { round ->
                val previous = state.value
                if (!sameRound(previous.active, round)) {
                    participationRevision++
                    submissionsRevision++
                    mutableState.value = previous.copy(active = round, submissions = emptyList(),
                        hasParticipated = false, submitting = false, loadingSubmissions = false, pendingVotes = emptySet(), error = null)
                } else mutableState.value = previous.copy(active = round, error = null)
                tick()
                refreshParticipation(round)
                if (current(expectedEpoch, round) && canReadSubmissions()) refreshSubmissions()
            }, onFailure = {
                mutableState.value = state.value.copy(error = "No pudimos actualizar la ronda. Puedes reintentar.")
            })
        } finally {
            if (current(expectedEpoch)) mutableState.value = state.value.copy(loadingRound = false)
        }
    }

    private suspend fun refreshParticipation(round: ActiveChallenge) {
        if (state.value.hasParticipated) return
        val owner = lease.accountId ?: return
        val expectedEpoch = epoch
        val revision = participationRevision
        val result = runReadWithDeadline(10_000) { source.participated(owner, round).getOrThrow() }
        currentCoroutineContext().ensureActive()
        if (!current(expectedEpoch, round) || revision != participationRevision) return
        result.onSuccess { mutableState.value = state.value.copy(hasParticipated = it) }
            .onFailure { mutableState.value = state.value.copy(error = "No pudimos comprobar tu participación. Reintenta.") }
    }

    private fun canReadSubmissions() = state.value.active != null &&
        (state.value.hasParticipated || state.value.active?.status != "active")

    fun loadSubmissions() {
        sessionChanged()
        if (lease.isCurrent()) tasks.scope.launch { refreshSubmissions() }
    }

    private suspend fun refreshSubmissions() {
        val round = state.value.active ?: return
        val owner = lease.accountId ?: return
        if (!lease.isCurrent() || state.value.loadingSubmissions) return
        val expectedEpoch = epoch
        val revision = submissionsRevision
        mutableState.value = state.value.copy(loadingSubmissions = true)
        try {
            val result = runReadWithDeadline(10_000) { source.submissions(owner, round).getOrThrow() }
            currentCoroutineContext().ensureActive()
            if (!current(expectedEpoch, round) || revision != submissionsRevision) return
            result.fold(onSuccess = { list ->
                val pending = state.value.pendingVotes
                val old = state.value.submissions.associateBy { it.id }
                val confirmed = list.filter { it.id.isNotBlank() && it.activeChallengeId == round.id && it.challengeId == round.challengeId }
                    .distinctBy { it.id }.map { row ->
                        if (row.id in pending) old[row.id]?.let {
                            row.copy(voteCount = it.voteCount, votedByMe = it.votedByMe)
                        } ?: row else row
                    }
                mutableState.value = state.value.copy(submissions = confirmed.sortedByDescending { it.voteCount })
            }, onFailure = {
                mutableState.value = state.value.copy(error = "No pudimos actualizar las respuestas. Conservamos las que ya cargaron.")
            })
        } finally {
            if (current(expectedEpoch, round)) mutableState.value = state.value.copy(loadingSubmissions = false)
        }
    }

    fun entry(): ChallengeEntry? {
        sessionChanged()
        val round = state.value.active
        if (!lease.isCurrent() || round == null || round.status != "active" || remainingSeconds(round, nowMillis()) == 0) {
            mutableState.value = state.value.copy(error = "Esta ronda ya terminó o todavía no cargó. Actualízala para continuar.")
            refresh()
            return null
        }
        return ChallengeEntry(round, epoch, lease)
    }

    private fun requireEntry(entry: ChallengeEntry, open: Boolean = false) {
        entry.lease.requireCurrent()
        if (!current(entry.sessionEpoch, entry.challenge)) throw ChallengeActionException("La ronda cambió. Vuelve a abrir la participación.")
        if (open && (state.value.active?.status != "active" || remainingSeconds(entry.challenge, nowMillis()) == 0))
            throw ChallengeActionException("El tiempo para participar terminó. Puedes ver los resultados al actualizar.")
    }

    suspend fun submit(entry: ChallengeEntry, operation: suspend (ActiveChallenge, String) -> Result<Unit>): Result<Unit> {
        val prepared = runCatching { requireEntry(entry, open = true); check(!state.value.submitting) { "Ya estamos enviando tu respuesta." } }
        prepared.exceptionOrNull()?.let { if (it is CancellationException) throw it; return Result.failure(it) }
        mutableState.value = state.value.copy(submitting = true, error = null)
        val work = tasks.scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = operation(entry.challenge, entry.lease.accountId!!)
                currentCoroutineContext().ensureActive()
                requireEntry(entry)
                result.fold(onSuccess = {
                    participationRevision++
                    submissionsRevision++
                    mutableState.value = state.value.copy(hasParticipated = true)
                    loadSubmissions()
                }, onFailure = { mutableState.value = state.value.copy(error = challengeError(it, "No pudimos enviar tu respuesta. Reintenta.")) })
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                entry.lease.requireCurrent()
                if (current(entry.sessionEpoch, entry.challenge))
                    mutableState.value = state.value.copy(error = "No pudimos enviar tu respuesta. Reintenta.")
                Result.failure(error)
            } finally {
                if (current(entry.sessionEpoch, entry.challenge)) mutableState.value = state.value.copy(submitting = false)
            }
        }
        return try { work.await() } finally { if (!work.isCompleted) work.cancel() }
    }

    fun toggleVote(id: String) {
        sessionChanged()
        val round = state.value.active ?: return
        val owner = lease.accountId ?: return
        if (!lease.isCurrent() || id in state.value.pendingVotes) return
        if (round.status !in setOf("active", "voting") || remainingSeconds(round, nowMillis()) == 0) {
            mutableState.value = state.value.copy(error = "La votación terminó. Actualiza para ver el resultado.")
            refresh(); return
        }
        val old = state.value.submissions.firstOrNull { it.id == id } ?: return
        val desired = !old.votedByMe
        val expectedEpoch = epoch
        submissionsRevision++
        mutableState.value = state.value.copy(pendingVotes = state.value.pendingVotes + id, error = null,
            submissions = state.value.submissions.map {
                if (it.id == id) it.copy(votedByMe = desired, voteCount = (it.voteCount + if (desired) 1 else -1).coerceAtLeast(0)) else it
            }.sortedByDescending { it.voteCount })
        tasks.scope.launch {
            try {
                val result = runReadWithDeadline(15_000) { source.vote(owner, round, id, desired).getOrThrow() }
                currentCoroutineContext().ensureActive()
                if (!current(expectedEpoch, round)) return@launch
                result.fold(onSuccess = { (count, voted) ->
                    mutableState.value = state.value.copy(submissions = state.value.submissions.map {
                        if (it.id == id) it.copy(voteCount = count.coerceAtLeast(0), votedByMe = voted) else it
                    }.sortedByDescending { it.voteCount })
                }, onFailure = {
                    mutableState.value = state.value.copy(error = "No pudimos confirmar el voto. Actualizaremos su estado.",
                        submissions = state.value.submissions.map { row -> if (row.id == id) old else row })
                })
                submissionsRevision++
                mutableState.value = state.value.copy(pendingVotes = state.value.pendingVotes - id)
                if (result.isFailure) refreshSubmissions() // Read back an uncertain ACK; never toggle again automatically.
            } finally {
                if (current(expectedEpoch, round)) mutableState.value = state.value.copy(pendingVotes = state.value.pendingVotes - id)
            }
        }
    }

    fun loadMine() {
        sessionChanged()
        val owner = lease.accountId ?: return
        if (!lease.isCurrent() || mineJob?.isActive == true) return
        val expectedEpoch = epoch
        val revision = mineRevision
        mineJob = tasks.scope.launch {
            val result = runReadWithDeadline(10_000) { source.mine(owner).getOrThrow() }
            currentCoroutineContext().ensureActive()
            if (!current(expectedEpoch) || revision != mineRevision) return@launch
            result.fold(onSuccess = { mutableState.value = state.value.copy(userChallenges = it, error = null) },
                onFailure = { mutableState.value = state.value.copy(error = "No pudimos cargar tus rondas. Reintenta.") })
        }
    }

    suspend fun mutate(operation: suspend (String) -> Result<Unit>): Result<Unit> {
        sessionChanged()
        val owner = lease.accountId ?: return Result.failure(IllegalStateException("Inicia sesión para continuar."))
        lease.requireCurrent()
        if (state.value.mutating) return Result.failure(IllegalStateException("Espera a que termine la operación anterior."))
        val expectedEpoch = epoch
        mutableState.value = state.value.copy(mutating = true, error = null)
        val work = tasks.scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = operation(owner)
                currentCoroutineContext().ensureActive()
                if (!current(expectedEpoch)) throw CancellationException("La sesión cambió.")
                if (result.isSuccess) {
                    mineRevision++
                    mineJob?.cancel()
                    mineJob = null
                }
                result.onFailure { mutableState.value = state.value.copy(error = challengeError(it, "No pudimos completar la operación. Reintenta.")) }
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!current(expectedEpoch)) throw CancellationException("La sesión cambió.")
                Result.failure(error)
            } finally {
                if (current(expectedEpoch)) mutableState.value = state.value.copy(mutating = false)
            }
        }
        return try { work.await() } finally { if (!work.isCompleted) work.cancel() }
    }

    fun close() { tasks.close() }
}

internal fun sameRound(a: ActiveChallenge?, b: ActiveChallenge?) =
    a != null && b != null && a.id == b.id && a.challengeId == b.challengeId

internal fun remainingSeconds(round: ActiveChallenge?, now: Long): Int {
    if (round == null) return 0
    val deadline = if (round.status == "voting") round.votingExpiresAt ?: round.expiresAt else round.expiresAt
    val millis = runCatching { Instant.parse(deadline).toEpochMilli() }.getOrNull() ?: return 0
    return ((millis - now + 999L) / 1_000L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}

internal fun challengeError(error: Throwable?, fallback: String): String =
    if (error is ChallengeActionException) error.message ?: fallback else fallback
