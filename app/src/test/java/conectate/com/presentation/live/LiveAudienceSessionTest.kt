package conectate.com.presentation.live

import conectate.com.data.localfallback.RecordingRealtimeSocket
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.model.LiveAudienceItem
import conectate.com.data.model.LiveAudienceState
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class LiveAudienceSessionTest {
    private class Fixture(dispatcher: CoroutineDispatcher) {
        val live = "7245c3f0-3a6a-4d9d-93c1-e20236506111"
        val guard = AuthSessionMutationGuard()
        var account: String? = "a"
        var token = "fixture-token"
        var ensureToken: suspend () -> Result<String> = { Result.success(token) }
        var request: suspend (HttpMethod, JsonObject?) -> Result<LiveAudienceState> = { _, _ ->
            Result.success(LiveAudienceState())
        }
        val writes = mutableListOf<JsonObject>()
        val realtime = UbuntuRealtimeClient(
            { guard.captureAccountLease { account } }, { token }, guard::commitForLease,
            { _, _ -> RecordingRealtimeSocket() },
        )
        val model = LiveAudienceViewModel(
            { _, _, _, method, body ->
                if (method != HttpMethod.Get) writes += requireNotNull(body)
                request(method, body)
            }, { guard.captureAccountLease { account } }, { ensureToken() }, guard::commitForLease,
            realtime, dispatcher,
        )
        fun aba() { guard.invalidate { account = "b" }; guard.invalidate { account = "a" } }
        fun state(text: String) = LiveAudienceState(items = listOf(
            LiveAudienceItem("question", "question", text),
        ))
    }

    @Test fun aReadReturningAfterAnUnobservedAbaCannotPublishToTheNewVisit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        val response = CompletableDeferred<LiveAudienceState>()
        f.request = { _, _ -> withContext(NonCancellable) { Result.success(response.await()) } }
        try {
            f.model.bind(f.live); runCurrent()
            f.aba()
            response.complete(f.state("Old visit")); runCurrent()
            assertNull(f.model.state.value)
        } finally { f.model.stop(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun tokenResolutionCannotSendAnOldDraftUsingTheNextVisitsCredentials() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        try {
            f.model.bind(f.live); runCurrent()
            val token = CompletableDeferred<Result<String>>()
            f.ensureToken = { token.await() }
            f.model.ask("Draft of the old visit"); runCurrent()
            assertTrue(f.model.busy.value)
            f.aba()
            token.complete(Result.success("token-of-new-visit")); runCurrent()
            assertTrue(f.writes.isEmpty())
            assertFalse(f.model.busy.value)
            assertEquals(0, f.model.createdVersion.value)
        } finally { f.model.stop(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun aFailedCreationKeepsItsNonceUntilTheServerConfirmsIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        try {
            f.model.bind(f.live); runCurrent()
            var failNext = true
            f.request = { method, _ ->
                if (method == HttpMethod.Post && failNext) {
                    failNext = false
                    Result.failure(IllegalStateException("Connection lost"))
                } else Result.success(f.state("Confirmed"))
            }
            f.model.ask("Same question"); runCurrent()
            assertNotNull(f.model.error.value)
            assertEquals(0, f.model.createdVersion.value)
            f.model.ask("Same question"); runCurrent()
            assertEquals(f.writes[0]["requestNonce"], f.writes[1]["requestNonce"])
            assertEquals(1, f.model.createdVersion.value)
            assertNull(f.model.error.value)
        } finally { f.model.stop(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun rebindingAfterAbaStartsANewDraftAndReadInsteadOfReusingTheOldJob() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        try {
            f.request = { method, _ -> if (method == HttpMethod.Get) Result.success(f.state("Old visit"))
                else Result.failure(IllegalStateException("Connection lost")) }
            f.model.bind(f.live); runCurrent()
            f.model.ask("Question"); runCurrent()
            val oldNonce = f.writes.single().getValue("requestNonce").jsonPrimitive.content
            f.aba()
            f.request = { method, _ -> if (method == HttpMethod.Get) Result.success(f.state("New visit"))
                else Result.failure(IllegalStateException("Connection lost")) }
            f.model.bind(f.live); runCurrent()
            assertEquals("New visit", f.model.state.value?.items?.single()?.content)
            f.model.ask("Question"); runCurrent()
            assertNotEquals(oldNonce, f.writes.last().getValue("requestNonce").jsonPrimitive.content)
        } finally { f.model.stop(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun resumingWaitsForThePreviousSubscriptionCleanup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        val response = CompletableDeferred<LiveAudienceState>()
        var tokenRequests = 0
        f.ensureToken = { tokenRequests++; Result.success("fixture-token") }
        f.request = { _, _ -> withContext(NonCancellable) { Result.success(response.await()) } }
        try {
            f.model.bind(f.live); runCurrent()
            val beforePause = tokenRequests
            f.model.stop()
            f.request = { _, _ -> Result.success(f.state("Resumed visit")) }
            f.model.bind(f.live); runCurrent()
            assertEquals(beforePause, tokenRequests)
            response.complete(f.state("Old response")); runCurrent()
            assertTrue(tokenRequests > beforePause)
            assertEquals("Resumed visit", f.model.state.value?.items?.single()?.content)
        } finally { f.model.stop(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun anIgnoredCreationWhileBusyCannotReplaceTheUnconfirmedNonce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        val response = CompletableDeferred<Result<LiveAudienceState>>()
        try {
            f.model.bind(f.live); runCurrent()
            f.request = { _, _ -> response.await() }
            f.model.ask("Original question"); runCurrent()
            f.model.ask("Ignored while sending"); runCurrent()
            response.complete(Result.failure(IllegalStateException("Lost acknowledgement"))); runCurrent()
            f.request = { _, _ -> Result.success(f.state("Confirmed")) }
            f.model.ask("Original question"); runCurrent()
            assertEquals(2, f.writes.size)
            assertEquals(f.writes.first()["requestNonce"], f.writes.last()["requestNonce"])
            assertEquals(1, f.model.createdVersion.value)
        } finally { f.model.stop(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun aReadReturningAfterThePanelStopsCannotPublish() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        val response = CompletableDeferred<LiveAudienceState>()
        f.request = { _, _ -> withContext(NonCancellable) { Result.success(response.await()) } }
        try {
            f.model.bind(f.live); runCurrent()
            f.model.stop()
            response.complete(f.state("Response after pause")); runCurrent()
            assertNull(f.model.state.value)
        } finally { f.model.stop(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun stoppingDuringTokenResolutionPreventsTheWrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        try {
            f.model.bind(f.live); runCurrent()
            val token = CompletableDeferred<Result<String>>()
            f.ensureToken = { withContext(NonCancellable) { token.await() } }
            f.model.ask("Question before pause"); runCurrent()
            f.model.stop()
            token.complete(Result.success("fixture-token")); runCurrent()
            assertTrue(f.writes.isEmpty())
            assertEquals(0, f.model.createdVersion.value)
            assertFalse(f.model.busy.value)
        } finally { f.model.stop(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun retryAfterStoppingCannotStartAnotherRead() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        var reads = 0
        f.request = { _, _ -> reads++; Result.success(f.state("Confirmed")) }
        try {
            f.model.bind(f.live); runCurrent()
            val beforePause = reads
            f.model.stop()
            f.model.retry(); runCurrent()
            assertEquals(beforePause, reads)
        } finally { f.model.stop(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun aLateCreateAfterStoppingKeepsItsNonceForResume() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val f = Fixture(dispatcher)
        val response = CompletableDeferred<LiveAudienceState>()
        try {
            f.model.bind(f.live); runCurrent()
            f.request = { method, _ -> if (method == HttpMethod.Post)
                withContext(NonCancellable) { Result.success(response.await()) }
                else Result.success(f.state("Current")) }
            f.model.ask("Same question"); runCurrent()
            val nonce = f.writes.single().getValue("requestNonce")
            f.model.stop()
            response.complete(f.state("Late confirmation")); runCurrent()
            assertEquals(0, f.model.createdVersion.value)
            f.request = { _, _ -> Result.success(f.state("Confirmed on resume")) }
            f.model.bind(f.live); runCurrent()
            f.model.ask("Same question"); runCurrent()
            assertEquals(nonce, f.writes.last().getValue("requestNonce"))
            assertEquals(1, f.model.createdVersion.value)
        } finally { f.model.stop(); runCurrent(); Dispatchers.resetMain() }
    }
}
