package conectate.com.data.repository

import conectate.com.data.localfallback.RecordingRealtimeSocket
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.localfallback.UbuntuRealtimeEnvelope
import conectate.com.data.model.LiveDrawGuessState
import conectate.com.data.model.LiveDrawPacket
import conectate.com.data.model.LiveDrawPacketReceipt
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class LiveDrawGuessAccountTest {
    private class Fixture(dispatcher: CoroutineDispatcher) {
        val live = "7245c3f0-3a6a-4d9d-93c1-e20236506111"
        val guard = AuthSessionMutationGuard()
        var account: String? = "a"
        var token: suspend (String) -> String? = { "token-$it" }
        val api = RecordingLiveDrawApi()
        val realtime = UbuntuRealtimeClient({ guard.captureAccountLease { account } }, { "token-$account" }, guard::commitForLease,
            { _, _ -> RecordingRealtimeSocket() })
        val repository = LiveDrawGuessRepository(api, { guard.captureAccountLease { account } }, { token(it) }, guard::commitForLease, realtime, dispatcher, guard.accountVisitChanges)
        fun aba() { guard.invalidate { account = "b" }; guard.invalidate { account = "a" } }
    }

    @Test fun aLateReadAfterAbaCannotPublishThePrivatePrompt() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val old = CompletableDeferred<Result<LiveDrawGuessState?>>()
        f.api.load = { withContext(NonCancellable) { old.await() } }
        val bind = backgroundScope.launch { f.repository.bind(f.live) }
        runCurrent(); f.aba()
        old.complete(Result.success(f.api.state("old", "Old private word"))); runCurrent()
        try { assertNull(f.repository.state.value) }
        finally { f.repository.unbind(); bind.cancel(); runCurrent() }
    }

    @Test fun anEventDuringTheInitialSnapshotIsObservedBeforeTheNextPollingInterval() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.api.load = {
            if (f.api.reads.size == 1) {
                val generation = f.realtime.javaClass.getDeclaredField("userSubscriptionGeneration").apply { isAccessible = true }.getLong(f.realtime)
                f.realtime.publishAcceptedEnvelope(UbuntuRealtimeEnvelope("live-game:${f.live}", "live_draw_guess",
                    buildJsonObject { put("type", "round_changed") }, subscriptionGeneration = generation))
                Result.success(f.api.state("first"))
            } else Result.success(f.api.state("updated-by-event"))
        }
        try {
            f.repository.bind(f.live); runCurrent()
            assertEquals(2, f.api.reads.size)
            assertEquals("updated-by-event", f.repository.state.value?.round?.id)
        } finally { f.repository.unbind(); runCurrent() }
    }

    @Test fun switchingAccountsClearsAnAlreadyDisplayedPrivatePromptWithoutAnotherRequest() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.api.load = { Result.success(f.api.state("round", "Private word")) }
        try {
            f.repository.bind(f.live); runCurrent()
            assertNotNull(f.repository.state.value?.privatePrompt)
            f.guard.invalidate { f.account = "b" }; runCurrent()
            assertNull(f.repository.state.value)
            assertEquals(1, f.api.reads.size)
        } finally { f.repository.unbind(); runCurrent() }
    }

    @Test fun anOldActionCannotUseTheTokenReturnedAfterAba() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val token = CompletableDeferred<String?>()
        f.token = { token.await() }
        val result = async { try { f.repository.create(f.live, listOf("guest")); false } catch (_: CancellationException) { true } }
        runCurrent(); f.aba(); token.complete("new-visit-token"); runCurrent()
        assertTrue(result.await())
        assertTrue(f.api.creations.isEmpty())
    }

    @Test fun rebindingTheSameLiveUnderAnotherAccountFetchesItsOwnState() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.api.load = { Result.success(f.api.state("round", if (it=="token-a") "Only A knows this" else null)) }
        try {
            f.repository.bind(f.live); runCurrent()
            assertNotNull(f.repository.state.value?.privatePrompt)
            f.guard.invalidate { f.account = "b" }
            f.repository.bind(f.live); runCurrent()
            assertEquals(listOf("token-a", "token-b"), f.api.reads)
            assertNull(f.repository.state.value?.privatePrompt)
        } finally { f.repository.unbind(); runCurrent() }
    }

    @Test fun leavingAndReturningToTheSameLiveInvalidatesAnOldSnapshot() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val old = CompletableDeferred<Result<LiveDrawGuessState?>>()
        val observed = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.repository.state.collect { observed += it?.round?.id }
        }
        try {
            f.repository.bind(f.live); runCurrent()
            f.api.load = { withContext(NonCancellable) { old.await() } }
            val read = backgroundScope.launch { f.repository.refresh(f.live) }; runCurrent()
            f.repository.unbind()
            f.api.load = { Result.success(f.api.state("new-visit")) }
            val bind = backgroundScope.launch { f.repository.bind(f.live) }; runCurrent()
            old.complete(Result.success(f.api.state("obsolete"))); runCurrent()
            assertEquals("new-visit", f.repository.state.value?.round?.id)
            assertFalse(observed.contains("obsolete"))
            assertTrue(read.isCompleted)
            assertTrue(bind.isCompleted)
        } finally { old.complete(Result.success(null)); f.repository.unbind(); runCurrent() }
    }

    @Test fun aDrawAcknowledgementForThePreviousRoundCannotAppendToTheNextRound() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Result<LiveDrawPacketReceipt>>()
        try {
            f.repository.bind(f.live); runCurrent()
            f.api.sendDraw = { response.await() }
            val packet = LiveDrawPacket(1, points = emptyList())
            val draw = async { f.repository.draw(f.live, "game", packet) }; runCurrent()
            f.repository.advance(f.live, "game")
            response.complete(Result.success(LiveDrawPacketReceipt(true))); runCurrent()
            assertTrue(draw.await().isSuccess)
            assertTrue(f.repository.drawPackets.value.isEmpty())
        } finally { response.complete(Result.success(LiveDrawPacketReceipt(true))); f.repository.unbind(); runCurrent() }
    }
}
