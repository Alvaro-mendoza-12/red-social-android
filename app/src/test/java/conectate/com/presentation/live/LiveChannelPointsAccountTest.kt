package conectate.com.presentation.live

import conectate.com.data.model.*
import conectate.com.data.repository.LiveChannelPointsRepository
import conectate.com.data.repository.RecordingLivePointsApi
import conectate.com.data.repository.MemoryLivePointRedemptions
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveChannelPointsAccountTest {
    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher, val api: RecordingLivePointsApi = RecordingLivePointsApi()) {
        val live = "7245c3f0-3a6a-4d9d-93c1-e20236506111"
        val guard = AuthSessionMutationGuard()
        var account: String? = "a"
        var token: suspend (String) -> String? = { "token-$it" }
        val repository = LiveChannelPointsRepository(api, { guard.captureAccountLease { account } }, { token(it) }, MemoryLivePointRedemptions())
        val controller = LiveChannelPointsController(repository, scope, { guard.captureAccountLease { account } }, guard::commitForLease, dispatcher, guard.accountVisitChanges)
        fun aba() { guard.invalidate { account = "b" }; guard.invalidate { account = "a" } }
    }

    @Test fun aRenewedTokenAfterAbaCannotDispatchTheOldRead() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val token = CompletableDeferred<String?>()
        f.token = { token.await() }
        val operation = async { try { f.repository.balance(f.live); false } catch (_: CancellationException) { true } }
        runCurrent(); f.aba(); token.complete("new-visit-token"); runCurrent()
        assertTrue(operation.await())
        assertTrue(f.api.balanceTokens.isEmpty())
    }

    @Test fun switchingAccountsClearsAlreadyDisplayedPointsWithoutAnotherRequest() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.api.readBalance = { Result.success(f.api.balance(31)) }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        assertEquals(31, f.controller.state.value.balance)
        f.guard.invalidate { f.account = "b" }; runCurrent()
        assertEquals(0, f.controller.state.value.balance)
        assertTrue(f.controller.state.value.rewards.isEmpty())
        assertFalse(f.controller.state.value.busy)
    }

    @Test fun ordinaryTokenRenewalPreservesTheDisplayedPoints() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.api.readBalance = { Result.success(f.api.balance(31)) }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        f.guard.commitIfCurrent(f.guard.snapshot()) { }; runCurrent()
        assertEquals(31, f.controller.state.value.balance)
    }

    @Test fun snapshotsRejectOldAndNewOwnersBeforeTheAccountObserverRuns() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.api.readBalance = { Result.success(f.api.balance(31)) }
        val old = f.guard.captureAccountLease { f.account }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        assertEquals(31, f.controller.stateFor(old, f.live).balance)
        assertEquals(31, f.controller.stateFor(old, "  ${f.live}  ").balance)
        assertEquals(0, f.controller.stateFor(old, "other-live").balance)
        f.aba() // Deliberately do not drain the generation collector.
        assertEquals(31, f.controller.state.value.balance)
        assertEquals(0, f.controller.stateFor(old, f.live).balance)
        assertEquals(0, f.controller.stateFor(f.guard.captureAccountLease { f.account }, f.live).balance)
    }

    @Test fun anOldPanelsDisposalCannotCancelTheNewAccountsRead() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val old = f.guard.captureAccountLease { f.account }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        f.aba()
        val response = CompletableDeferred<Result<LivePointsBalanceResponse>>()
        f.api.readBalance = { response.await() }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        f.controller.stopChannelPoints(old, f.live)
        response.complete(Result.success(f.api.balance(20))); runCurrent()
        assertEquals(20, f.controller.state.value.balance)
        assertFalse(f.controller.state.value.busy)
    }

    @Test fun scopedDisposalAcceptsTheSameNormalizedLiveIdAsTheStart() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val owner = f.guard.captureAccountLease { f.account }
        f.controller.startChannelPoints("  ${f.live}  "); runCurrent()
        assertTrue(f.controller.state.value.sessionActive)
        f.controller.stopChannelPoints(owner, "  ${f.live}  "); runCurrent()
        assertFalse(f.controller.state.value.sessionActive)
    }

    @Test fun rewardCreationIsAcknowledgedOnlyAfterSuccessEvenIfTheFollowingReadFails() = runTest {
        var fail = true
        val api = object : RecordingLivePointsApi() {
            override suspend fun createReward(token: String, liveId: String, title: String, description: String, cost: Int, stock: Int?, cooldownSeconds: Int): Result<LivePointRewardResponse> =
                if (fail) Result.failure(IllegalStateException("Offline")) else super.createReward(token, liveId, title, description, cost, stock, cooldownSeconds)
        }
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler), api)
        f.controller.createChannelPointReward(f.live, "Título", "Texto", 10, null, 0); runCurrent()
        assertEquals(0, f.controller.state.value.rewardCreationVersion)
        fail = false
        api.readBalance = { Result.failure(IllegalStateException("Refresh unavailable")) }
        f.controller.createChannelPointReward(f.live, "Título", "Texto", 10, null, 0); runCurrent()
        assertEquals(1, f.controller.state.value.rewardCreationVersion)
        assertNotNull(f.controller.state.value.message)
        assertFalse(f.controller.state.value.busy)
    }

    @Test fun aLateCreationAcknowledgementCannotClearAnotherVisitsDraft() = runTest {
        val response = CompletableDeferred<Result<LivePointRewardResponse>>()
        val api = object : RecordingLivePointsApi() {
            override suspend fun createReward(token: String, liveId: String, title: String, description: String, cost: Int, stock: Int?, cooldownSeconds: Int) =
                withContext(NonCancellable) { response.await() }
        }
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler), api)
        f.controller.createChannelPointReward(f.live, "Viejo", "", 10, null, 0); runCurrent()
        f.aba()
        f.controller.refreshChannelPoints(f.live); runCurrent()
        response.complete(Result.success(LivePointRewardResponse(LivePointReward("reward", f.live, "a", "Viejo", cost = 10))))
        runCurrent()
        assertEquals(0, f.controller.state.value.rewardCreationVersion)
        assertFalse(f.controller.state.value.busy)
    }

    @Test fun aResponseAfterSwitchingAccountsCannotReturnAsCurrent() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Result<LivePointsBalanceResponse>>()
        f.api.readBalance = { response.await() }
        val operation = async { try { f.repository.balance(f.live); false } catch (_: CancellationException) { true } }
        runCurrent(); f.guard.invalidate { f.account = "b" }
        response.complete(Result.success(f.api.balance(99))); runCurrent()
        assertTrue(operation.await())
    }

    @Test fun aQueuedRefreshCannotBorrowTheNextAccountsCredentials() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.controller.refreshChannelPoints(f.live)
        f.aba(); runCurrent()
        assertTrue(f.api.balanceTokens.isEmpty())
        assertEquals(0, f.controller.state.value.balance)
    }

    @Test fun aLateReadCannotOverwriteTheNewAccountsBalance() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val old = CompletableDeferred<Result<LivePointsBalanceResponse>>()
        f.api.readBalance = { if (it == "token-a") withContext(NonCancellable) { old.await() } else Result.success(f.api.balance(20)) }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        f.guard.invalidate { f.account = "b" }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        old.complete(Result.success(f.api.balance(99))); runCurrent()
        assertEquals(20, f.controller.state.value.balance)
        assertFalse(f.controller.state.value.busy)
    }

    @Test fun stoppingThePanelPreventsAnUntrackedReadFromPublishing() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Result<LivePointsBalanceResponse>>()
        f.api.readBalance = { withContext(NonCancellable) { response.await() } }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        f.controller.stopChannelPoints()
        response.complete(Result.success(f.api.balance(99))); runCurrent()
        assertEquals(0, f.controller.state.value.balance)
        assertFalse(f.controller.state.value.busy)
    }

    @Test fun repeatedRedemptionWhileSendingDoesNotCreateAnotherDebit() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Result<LivePointRedemptionResponse>>()
        f.api.redeemReward = { response.await() }
        f.controller.redeemChannelPointReward(f.live, "reward")
        f.controller.redeemChannelPointReward(f.live, "reward"); runCurrent()
        val requests = f.api.redemptionNonces.size
        response.complete(Result.success(f.api.redemption())); runCurrent()
        assertEquals(1, requests)
    }

    @Test fun losingTheRedemptionAcknowledgementKeepsTheNonceForRetry() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        var fail = true
        f.api.redeemReward = { if (fail) { fail = false; Result.failure(IllegalStateException("Lost acknowledgement")) } else Result.success(f.api.redemption()) }
        f.controller.redeemChannelPointReward(f.live, "reward"); runCurrent()
        f.controller.redeemChannelPointReward(f.live, "reward"); runCurrent()
        assertEquals(2, f.api.redemptionNonces.size)
        assertEquals(f.api.redemptionNonces.first(), f.api.redemptionNonces.last())
    }

    @Test fun cancellationDoesNotLeaveThePanelBusy() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.api.readBalance = { throw CancellationException("Panel left") }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        assertFalse(f.controller.state.value.busy)
    }

    @Test fun aFailedRedemptionHistoryReadPreservesTheConfirmedList() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val previous = f.api.redemption().redemption
        f.api.readRedemptions = { Result.success(LivePointRedemptionsResponse(listOf(previous))) }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        f.api.readRedemptions = { Result.failure(IllegalStateException("Offline")) }
        f.controller.refreshChannelPoints(f.live); runCurrent()
        assertEquals(listOf(previous), f.controller.state.value.redemptions)
        assertNotNull(f.controller.state.value.message)
    }
}
