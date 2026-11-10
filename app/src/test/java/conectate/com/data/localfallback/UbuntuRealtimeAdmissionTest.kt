package conectate.com.data.localfallback

import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual accepted-event buffer without connecting a socket or using real tokens. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class UbuntuRealtimeAdmissionTest {
    private fun envelope(id: String, subscription: Long = 0L, connection: Long = 0L) =
        UbuntuRealtimeEnvelope("user:a", id, JsonObject(emptyMap()), connection, subscription)

    private fun reconnectWithoutCredentials(client: UbuntuRealtimeClient) {
        // Exercise the production reconnect path. With a blank access token it opens no socket.
        client.javaClass.getDeclaredMethod("reconnect").apply { isAccessible = true }.invoke(client)
    }

    @Test fun subscriptionHookRunsAfterRegistrationSoReadinessCannotBeLost() = runTest {
        val client = UbuntuRealtimeClient()
        val received = async(UnconfinedTestDispatcher(testScheduler)) {
            client.eventsOnSubscription {
                client.publishAcceptedEnvelope(envelope("subscription_ready"))
            }.first()
        }
        runCurrent()
        assertTrue("The hook must run after the SharedFlow collector is registered", received.isCompleted)
        assertEquals("subscription_ready", received.await().event)
    }

    @Test fun subscriptionHookCannotBypassAccountVisitFiltering() = runTest {
        val client = UbuntuRealtimeClient()
        client.clearUserSubscriptions()
        val received = client.eventsOnSubscription {
            emit(envelope("stale"))
            emit(envelope("current", subscription = 1L))
        }.first()
        assertEquals("current", received.event)
    }

    @Test fun anAcceptedEnvelopeRemainsCurrentAfterSameSessionReconnect() {
        val client = UbuntuRealtimeClient()
        val admitted = envelope("accepted")
        assertTrue(client.isCurrent(admitted))
        reconnectWithoutCredentials(client)
        assertTrue(client.isCurrent(admitted))
    }

    @Test fun bufferedEventsSurviveSameSessionReconnect() = runTest {
        val client = UbuntuRealtimeClient()
        val releaseConsumer = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.events.collect { event ->
                if (event.event == "barrier") releaseConsumer.await()
                received += event.event
            }
        }
        client.publishAcceptedEnvelope(envelope("barrier"))
        client.publishAcceptedEnvelope(envelope("queued"))
        reconnectWithoutCredentials(client)
        releaseConsumer.complete(Unit)
        runCurrent()
        assertEquals(listOf("barrier", "queued"), received)
    }

    @Test fun receivedFrameAwaitingDispatchSurvivesSameSessionReconnect() = runTest {
        val client = UbuntuRealtimeClient()
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.events.collect { received += it.event }
        }
        val acceptedBeforeReconnect = envelope("accepted")
        reconnectWithoutCredentials(client)
        client.publishAcceptedEnvelope(acceptedBeforeReconnect)
        runCurrent()
        assertEquals(listOf("accepted"), received)
    }

    @Test fun accountVisitChangeDiscardsBufferedAndLateEventsIncludingAba() = runTest {
        val client = UbuntuRealtimeClient()
        val releaseConsumer = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.events.collect { event ->
                if (event.event == "barrier") releaseConsumer.await()
                received += event.event
            }
        }
        client.publishAcceptedEnvelope(envelope("barrier"))
        client.publishAcceptedEnvelope(envelope("old-buffered"))
        client.clearUserSubscriptions()
        client.replaceUserSubscription("a") // Same UID, new visit. No access token means no network.
        assertFalse(client.isCurrent(envelope("old-visit")))
        releaseConsumer.complete(Unit)
        client.publishAcceptedEnvelope(envelope("old-late"))
        client.publishAcceptedEnvelope(envelope("new-visit", subscription = 2L, connection = 1L))
        runCurrent()
        assertEquals(listOf("barrier", "new-visit"), received)
    }
}
