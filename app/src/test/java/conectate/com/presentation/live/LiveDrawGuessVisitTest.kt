package conectate.com.presentation.live

import conectate.com.data.localfallback.RecordingRealtimeSocket
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.model.LiveDrawGuessState
import conectate.com.data.repository.LiveDrawGuessRepository
import conectate.com.data.repository.RecordingLiveDrawApi
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class LiveDrawGuessVisitTest {
    private class Fixture(val dispatcher: CoroutineDispatcher) {
        val live = "7245c3f0-3a6a-4d9d-93c1-e20236506111"
        val guard = AuthSessionMutationGuard()
        var account: String? = "a"
        val api = RecordingLiveDrawApi()
        val realtime = UbuntuRealtimeClient({ guard.captureAccountLease { account } }, { "token-$account" }, guard::commitForLease,
            { _, _ -> RecordingRealtimeSocket() })
        val repository = LiveDrawGuessRepository(api, { guard.captureAccountLease { account } }, { "token-$it" },
            guard::commitForLease, realtime, dispatcher, guard.accountVisitChanges)
        val viewModel = LiveDrawGuessViewModel(repository, dispatcher)
    }

    private suspend fun TestScope.withFixture(test: suspend Fixture.() -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        try { f.test() }
        finally { f.viewModel.unbind(); f.repository.unbind(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun closingThePanelDropsALateFailureAndAllowsANewVisitImmediately() = runTest {
        withFixture {
            val old = CompletableDeferred<Result<LiveDrawGuessState>>()
            val first = viewModel.bind(live)
            runCurrent()
            api.create = { withContext(NonCancellable) { old.await() } }
            viewModel.create(listOf("guest")); viewModel.create(listOf("guest")); runCurrent()
            assertTrue(viewModel.busy.value)
            assertEquals(1, api.creations.size)
            viewModel.unbind(first)
            assertFalse(viewModel.busy.value)
            api.load = { Result.success(api.state("new-visit")) }
            viewModel.bind(live); runCurrent()
            old.complete(Result.failure(IllegalStateException("Error from the old panel"))); runCurrent()
            assertNull(viewModel.error.value)
            assertFalse(viewModel.busy.value)
            assertEquals("new-visit", viewModel.gameState.value?.round?.id)
        }
    }

    @Test fun anOldLifecycleCleanupCannotCloseTheNewlyResumedPanel() = runTest {
        withFixture {
            val old = viewModel.bind(live); runCurrent()
            viewModel.unbind(old)
            api.load = { Result.success(api.state("resumed")) }
            viewModel.bind(live); runCurrent()
            viewModel.unbind(old); runCurrent()
            assertTrue(viewModel.isCurrentVisit(live, "a"))
            assertEquals("resumed", viewModel.gameState.value?.round?.id)
        }
    }

    @Test fun closingAnOlderViewModelDoesNotUnsubscribeTheCurrentViewModel() = runTest {
        withFixture {
            val first = viewModel.bind(live); runCurrent()
            val next = LiveDrawGuessViewModel(repository, dispatcher)
            try {
                api.load = { Result.success(api.state("second-view-model")) }
                next.bind(live); runCurrent()
                viewModel.unbind(first); runCurrent()
                assertTrue(next.isCurrentVisit(live, "a"))
                assertEquals("second-view-model", next.gameState.value?.round?.id)
            } finally { next.unbind(); runCurrent() }
        }
    }

    @Test fun aQueuedActionCannotUseTheNextAccountAfterReturningToTheSameIdentity() = runTest {
        withFixture {
            viewModel.bind(live); runCurrent()
            viewModel.create(listOf("guest"))
            guard.invalidate { account = "b" }; guard.invalidate { account = "a" }; runCurrent()
            assertTrue(api.creations.isEmpty())
            assertFalse(viewModel.busy.value)
            assertNull(viewModel.gameState.value)
            assertFalse(viewModel.isCurrentVisit(live, "a"))
        }
    }

    @Test fun cancellationClearsBusyAndTheSameVisitCanRetryTheAction() = runTest {
        withFixture {
            viewModel.bind(live); runCurrent()
            api.create = { throw CancellationException("Request cancelled") }
            viewModel.create(listOf("guest")); runCurrent()
            assertFalse(viewModel.busy.value)
            assertNull(viewModel.error.value)
            api.create = { Result.success(api.state("retried")) }
            viewModel.create(listOf("guest")); runCurrent()
            assertFalse(viewModel.busy.value)
            assertEquals("retried", viewModel.gameState.value?.round?.id)
        }
    }

    @Test fun aChangedAccountCannotRenderTheOldPromptBeforeTheObserverRuns() = runTest {
        withFixture {
            api.load = { Result.success(api.state("round", "Only A can see this")) }
            viewModel.bind(live); runCurrent()
            assertTrue(viewModel.isCurrentVisit(live, "a"))
            guard.invalidate { account = "b" }
            assertFalse(viewModel.isCurrentVisit(live, "b"))
            runCurrent()
            assertNull(viewModel.gameState.value)
            assertNull(viewModel.guessResult.value)
        }
    }
}
