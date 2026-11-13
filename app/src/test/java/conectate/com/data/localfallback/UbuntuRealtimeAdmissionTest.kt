package conectate.com.data.localfallback

import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class UbuntuRealtimeAdmissionTest {
    private class Fixture {
        val guard = AuthSessionMutationGuard()
        var account: String? = "a"
        var token = ""
        val sockets = mutableListOf<RecordingRealtimeSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val client = UbuntuRealtimeClient(
            { guard.captureAccountLease { account } }, { token }, guard::commitForLease,
            { _: Request, listener: WebSocketListener ->
                RecordingRealtimeSocket().also { sockets += it; listeners += listener }
            },
        ).also { it.replaceUserSubscription("a") }

        fun envelope(event: String): UbuntuRealtimeEnvelope {
            fun counter(name: String) = client.javaClass.getDeclaredField(name).apply { isAccessible = true }.getLong(client)
            return UbuntuRealtimeEnvelope("user:$account", event, JsonObject(emptyMap()),
                counter("socketGeneration"), counter("userSubscriptionGeneration"))
        }
        fun switchTo(next: String?) { guard.invalidate { account = next } }
    }

    @Test fun registrationPrecedesTheSubscriptionHook() = runTest {
        val f = Fixture()
        val ready = async(UnconfinedTestDispatcher(testScheduler)) {
            f.client.eventsOnSubscription { f.client.publishAcceptedEnvelope(f.envelope("ready")) }.first()
        }
        runCurrent()
        assertTrue(ready.isCompleted)
        assertEquals("ready", ready.await().event)
    }

    @Test fun hooksCannotBypassFilteringForAnOldVisit() = runTest {
        val f = Fixture()
        val old = f.envelope("old")
        f.switchTo("b"); f.switchTo("a")
        f.client.replaceUserSubscription("a")
        val received = f.client.eventsOnSubscription { emit(old); emit(f.envelope("new")) }.first()
        assertEquals("new", received.event)
    }

    @Test fun eightConsumersOfTheSameAccountDoNotReconnectOrRevokeAcceptedEvents() {
        val f = Fixture()
        f.token = "fixture-token"; f.client.updateAccessToken(f.token)
        val admitted = f.envelope("accepted")
        repeat(8) { f.client.replaceUserSubscription(if (it % 2 == 0) " A " else "a") }
        assertTrue(f.client.isCurrent(admitted))
        assertEquals(1, f.sockets.size)
        assertTrue(f.sockets.single().closes.isEmpty())
    }

    @Test fun registeringTheSameUserAfterOpenDoesNotSendDuplicateSubscriptions() {
        val f = Fixture()
        f.token = "fixture-token"; f.client.updateAccessToken(f.token)
        val socket = f.sockets.single()
        f.listeners.single().onOpen(socket, Response.Builder().request(socket.request())
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        f.listeners.single().onMessage(socket, """{"type":"connected","userId":"a"}""")
        repeat(8) { f.client.replaceUserSubscription("a") }
        assertEquals(listOf("""{"type":"subscribe","channel":"user:a"}"""), socket.sent)
    }

    @Test fun bufferedEventsSurviveRepeatedSubscriptionsAndTokenRotation() = runTest {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.client.events.collect { event ->
                if (event.event == "barrier") release.await()
                received += event.event
            }
        }
        f.client.publishAcceptedEnvelope(f.envelope("barrier"))
        val queued = f.envelope("queued")
        f.client.publishAcceptedEnvelope(queued)
        repeat(8) { f.client.replaceUserSubscription("a") }
        f.token = "fixture-rotated-token"; f.client.updateAccessToken(f.token)
        assertTrue(f.client.isCurrent(queued))
        release.complete(Unit); runCurrent()
        assertEquals(listOf("barrier", "queued"), received)
    }

    @Test fun aFrameAwaitingDispatchSurvivesReconnectWithinItsVisit() = runTest {
        val f = Fixture()
        val admitted = f.envelope("accepted")
        f.token = "fixture-token"; f.client.updateAccessToken(f.token)
        val received = async(UnconfinedTestDispatcher(testScheduler)) { f.client.events.first() }
        f.client.publishAcceptedEnvelope(admitted)
        assertEquals(admitted, received.await())
    }

    @Test fun abaInvalidatesAdmissionBeforeAnySubscriptionOrCleanupRuns() {
        val f = Fixture()
        val admitted = f.envelope("old")
        f.switchTo("b"); f.switchTo("a")
        assertFalse(f.client.isCurrent(admitted))
        f.client.replaceUserSubscription("a")
        assertFalse(f.client.isCurrent(admitted))
        assertTrue(f.client.isCurrent(f.envelope("new")))
    }

    @Test fun bufferedAndLateFramesCannotEnterTheNewVisit() = runTest {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.client.events.collect {
                if (it.event == "barrier") release.await()
                received += it.event
            }
        }
        f.client.publishAcceptedEnvelope(f.envelope("barrier"))
        val old = f.envelope("old")
        f.client.publishAcceptedEnvelope(old)
        f.switchTo(null); f.client.clearUserSubscriptions()
        f.switchTo("a"); f.client.replaceUserSubscription("a")
        release.complete(Unit)
        f.client.publishAcceptedEnvelope(old)
        f.client.publishAcceptedEnvelope(f.envelope("new")); runCurrent()
        assertEquals(listOf("barrier", "new"), received)
    }

    @Test fun lateCleanupCannotEraseTheNewAccountOrTheCurrentVisit() {
        val f = Fixture()
        f.switchTo("b"); f.client.replaceUserSubscription("b")
        val current = f.envelope("current")
        f.client.clearUserSubscriptions()
        assertTrue(f.client.isCurrent(current))
    }

    @Test fun wrongAccountSubscriptionAndObsoleteCredentialsAreIgnored() {
        val f = Fixture()
        val current = f.envelope("current")
        f.client.replaceUserSubscription("b")
        f.client.updateAccessToken("obsolete-fixture-token")
        assertTrue(f.client.isCurrent(current))
        assertTrue(f.sockets.isEmpty())
    }

    @Test fun aLateSocketCallbackCannotBeAdmittedAfterAba() {
        val f = Fixture()
        f.token = "fixture-token"; f.client.updateAccessToken(f.token)
        val oldListener = f.listeners.single()
        val oldSocket = f.sockets.single()
        f.switchTo("b"); f.switchTo("a")
        oldListener.onMessage(oldSocket, """{"type":"event","channel":"user:a","payload":{"event":"late"}}""")
        f.client.replaceUserSubscription("a")
        assertEquals(1, f.sockets.size) // Subscribing never reuses the revoked visit's token.
        assertEquals(listOf(1000 to "account-changed"), oldSocket.closes)
    }
}
