package conectate.com.data.auth

import conectate.com.data.localfallback.LocalFallbackAuthResponse
import conectate.com.data.localfallback.LocalFallbackUser
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSwitchCoordinatorTest {
    private class Fixture {
        val guard = AuthSessionMutationGuard()
        var active: String? = "a"
        var activeResponse = response("a")
        var pending = listOf("a-message")
        val archives = mutableMapOf("b" to listOf("b-message"))
        val storedTokens = mutableMapOf("a" to "a-old", "b" to "b-old")
        val errors = mutableListOf<Exception>()
        var failureAt: String? = null
        var target = "b"
        var gate: suspend (String) -> Unit = {}
        lateinit var session: AccountSwitchSession
        val activationMutex = Mutex()
        private fun response(id: String) = LocalFallbackAuthResponse(
            LocalFallbackUser(id = id, email = "$id@example.test"), "$id-renewed", "$id-refresh",
        )
        fun prepared() = PreparedLocalAccount(
            StoredAccountSession(id = "$target-saved", user_id = target, email = "$target@example.test",
                access_token = "$target-renewed", refresh_token = "$target-refresh"),
            LocalFallbackAuthResponse(LocalFallbackUser(id = target, email = "$target@example.test"), "$target-renewed", "$target-refresh"),
        )
        private suspend fun checkpoint(stage: String) {
            gate(stage)
            if (failureAt == stage) error("failed-$stage")
        }
        fun runner(rollbackTimeout: Long = 20_000) = AccountSwitchCoordinator(
            open = {
                var pauses = 0
                LocalAccountSwitchSession(
                    guard, activationMutex, currentAccount = { active },
                    capture = { active?.let { activeResponse } },
                    saveCurrent = { outgoing ->
                        if (failureAt == "snapshot") error("failed-snapshot")
                        storedTokens[outgoing.user.id] = outgoing.accessToken
                    },
                    pauseCurrent = { active = null; pauses++ }, clearCaches = {},
                    archive = { owner ->
                        if (pauses > 1) checkpoint("rollback")
                        if (pending.isNotEmpty()) archives[checkNotNull(owner)] = (archives[owner].orEmpty() + pending).distinct()
                        pending = emptyList()
                        if (pauses == 1) checkpoint("paused")
                    },
                    activateResponse = { response, lease, compatibility ->
                        activationMutex.withLock {
                            if (compatibility) assertEquals("$target-renewed", storedTokens[target])
                            check(guard.advanceTransition(lease) { active = response.user.id; activeResponse = response })
                        }
                        if (compatibility) checkpoint("activated")
                    },
                    restore = { owner ->
                        pending = (pending + archives.remove(owner).orEmpty()).distinct()
                        if (owner == target && owner != "a") checkpoint("restored")
                    }, afterPause = {},
                ).also { session = it }
            },
            prepare = {
                checkpoint("prepare")
                prepared().also { storedTokens[target] = it.stored.access_token }
            },
            complete = { _, _ -> checkpoint("complete"); checkNotNull(active) },
            onRollbackFailure = { errors += it }, rollbackTimeoutMillis = rollbackTimeout,
        )
    }

    @Test fun completesWithTheTargetOutboxAndKeepsTheOriginalOutboxArchived() = runTest {
        val f = Fixture()
        assertEquals("b", f.runner().switch("b").getOrThrow())
        assertEquals("b", f.active)
        assertEquals(listOf("b-message"), f.pending)
        assertEquals(listOf("a-message"), f.archives["a"])
        assertEquals("b-renewed", f.storedTokens["b"])
        assertFalse(f.session.isCurrent)
    }

    @Test fun aPreparationFailureLeavesTheOriginalSessionAndOutboxActive() = runTest {
        val f = Fixture().apply { failureAt = "prepare" }
        assertTrue(f.runner().switch("b").isFailure)
        assertEquals("a", f.active)
        assertEquals(listOf("a-message"), f.pending)
        assertFalse(f.session.hasPaused)
    }

    @Test fun signingIntoTheSameIdentityKeepsItsPendingMessagesInPlace() = runTest {
        val f = Fixture().apply { target = "a" }
        assertEquals("a", f.runner().switch("a").getOrThrow())
        assertEquals(listOf("a-message"), f.pending)
        assertFalse(f.session.hasPaused)
        assertEquals("a-renewed", f.storedTokens["a"])
        assertEquals(listOf("b-message"), f.archives["b"])
    }

    @Test fun openingFailureDoesNotStartPreparationOrLoseTheOriginalError() = runTest {
        val runner = AccountSwitchCoordinator<String>(
            open = { error("secure-store-unavailable") },
            prepare = { error("Preparation must not run") },
            complete = { _, _ -> error("Completion must not run") }, onRollbackFailure = { fail() },
        )
        assertEquals("secure-store-unavailable", runner.switch("b").exceptionOrNull()?.message)
    }

    @Test fun aFailedOriginalSnapshotDoesNotPauseTheAccountButKeepsTheRenewedTarget() = runTest {
        val f = Fixture().apply { failureAt = "snapshot" }
        assertTrue(f.runner().switch("b").isFailure)
        assertEquals("a", f.active)
        assertEquals(listOf("a-message"), f.pending)
        assertEquals("b-renewed", f.storedTokens["b"])
    }

    @Test fun failuresAfterPauseActivationOrRestoreRecoverTheOriginalOutbox() = runTest {
        for (stage in listOf("paused", "activated", "restored", "complete")) {
            val f = Fixture().apply { failureAt = stage }
            assertTrue(stage, f.runner().switch("b").isFailure)
            assertEquals(stage, "a", f.active)
            assertEquals(stage, listOf("a-message"), f.pending)
            assertEquals("b-renewed", f.storedTokens["b"])
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun cancellationDuringCompletionRestoresLocallyAndStillPropagatesCancellation() = runTest {
        val f = Fixture()
        val reached = CompletableDeferred<Unit>()
        f.gate = { if (it == "complete") { reached.complete(Unit); awaitCancellation() } }
        val task = async { f.runner().switch("b") }
        reached.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals("a", f.active)
        assertEquals(listOf("a-message"), f.pending)
        assertFalse(f.session.isCurrent)
    }

    @Test fun anExplicitLogoutIsNeverUndoneByAnOlderFailedSwitch() = runTest {
        val f = Fixture()
        f.gate = { if (it == "complete") f.guard.invalidate { f.active = null; f.pending = emptyList() } }
        val error = runCatching { f.runner().switch("b") }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertNull(f.active)
        assertTrue(f.pending.isEmpty())
        assertEquals(listOf("a-message"), f.archives["a"])
    }

    @Test fun aNewLoginToTheSameTargetIsNeverReplacedByRollback() = runTest {
        val f = Fixture()
        f.gate = { if (it == "complete") f.guard.invalidate { f.active = "b"; f.pending = listOf("new-login-b") } }
        assertTrue(runCatching { f.runner().switch("b") }.exceptionOrNull() is CancellationException)
        assertEquals("b", f.active)
        assertEquals(listOf("new-login-b"), f.pending)
    }

    @Test fun aSessionChangeDuringPreparationCannotPauseTheNewAccount() = runTest {
        val f = Fixture()
        f.gate = { if (it == "prepare") f.guard.invalidate { f.active = "c"; f.pending = listOf("c-message") } }
        assertTrue(runCatching { f.runner().switch("b") }.exceptionOrNull() is CancellationException)
        assertEquals("c", f.active)
        assertEquals(listOf("c-message"), f.pending)
        assertFalse(f.session.hasPaused)
    }

    @Test fun rollbackFailureIsReportedAndDoesNotReplaceTheOriginalError() = runTest {
        val f = Fixture().apply { failureAt = "complete" }
        f.gate = { if (it == "rollback") error("recovery-disk-error") }
        assertEquals("failed-complete", f.runner().switch("b").exceptionOrNull()?.message)
        assertEquals("recovery-disk-error", f.errors.single().message)
        assertFalse(f.session.isCurrent)
    }

    @Test fun anUnresponsiveLocalRecoveryHasABoundedDeadline() = runTest {
        val f = Fixture().apply { failureAt = "complete" }
        f.gate = { if (it == "rollback") awaitCancellation() }
        assertEquals("failed-complete", f.runner(100).switch("b").exceptionOrNull()?.message)
        assertTrue(f.errors.single() is TimeoutCancellationException)
        assertFalse(f.session.isCurrent)
    }

    @Test fun aNewSessionDuringRollbackDoesNotReceiveTheOldFailure() = runTest {
        val f = Fixture().apply { failureAt = "complete" }
        f.gate = { if (it == "rollback") {
            f.guard.invalidate { f.active = "c"; f.pending = listOf("c-message") }
            error("old-recovery-error")
        } }
        assertTrue(runCatching { f.runner().switch("b") }.exceptionOrNull() is CancellationException)
        assertEquals("c", f.active)
        assertEquals(listOf("c-message"), f.pending)
        assertTrue(f.errors.isEmpty())
    }
}
