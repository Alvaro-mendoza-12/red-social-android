package conectate.com.data.localfallback

import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual transport callbacks, without a network or a gateway with relaxed admission limits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class UbuntuRealtimeSubscriptionsTest {
    private class Fixture {
        val guard = AuthSessionMutationGuard()
        var account: String? = "a"
        var token = "fixture-token"
        val sockets = mutableListOf<RecordingRealtimeSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val client = UbuntuRealtimeClient(
            { guard.captureAccountLease { account } }, { token }, guard::commitForLease,
            { _: Request, listener -> RecordingRealtimeSocket().also { sockets += it; listeners += listener } },
        ).also { it.replaceUserSubscription("a"); it.updateAccessToken(token) }

        fun tcpOpen(index: Int = sockets.lastIndex) {
            val socket = sockets[index]
            listeners[index].onOpen(socket, Response.Builder().request(socket.request())
                .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        }

        fun frame(type: String, channel: String? = null, index: Int = sockets.lastIndex) {
            listeners[index].onMessage(sockets[index], buildJsonObject {
                put("type", type)
                channel?.let { put("channel", it) }
                if (type == "error") put("error", "FORBIDDEN_CHANNEL")
            }.toString())
        }

        fun sent(type: String, index: Int = sockets.lastIndex) = sockets[index].sent.map {
            Json.parseToJsonElement(it).jsonObject
        }.filter { it["type"]?.jsonPrimitive?.content == type }.map { it.getValue("channel").jsonPrimitive.content }
    }

    @Test fun waitsForAuthenticatedConnectedFrameAndDrainsThreeAtATime() {
        val f = Fixture()
        repeat(8) { f.client.subscribe("live-audience:room-$it") }
        f.tcpOpen()
        assertTrue("TCP open does not acknowledge authentication", f.sockets.single().sent.isEmpty())
        f.frame("connected")
        assertEquals(3, f.sent("subscribe").size)
        var acknowledged = 0
        while (acknowledged < 9) {
            val sent = f.sent("subscribe")
            assertTrue(sent.size - acknowledged <= 3)
            assertTrue("A reply must release a slot for the next room", sent.size > acknowledged)
            f.frame("subscribed", sent[acknowledged++])
        }
        assertEquals(9, f.sent("subscribe").distinct().size)
        f.frame("connected")
        repeat(4) { f.client.subscribe("live-audience:room-0") }
        assertEquals("Repeated consumers do not duplicate a subscription", 9, f.sent("subscribe").size)
    }

    @Test fun aDeniedChannelReleasesItsSlotWithoutBlockingTheRest() {
        val f = Fixture()
        repeat(5) { f.client.subscribe("live-audience:room-$it") }
        f.tcpOpen(); f.frame("connected")
        val denied = f.sent("subscribe").first()
        f.frame("error", denied)
        assertEquals(4, f.sent("subscribe").size)
        f.frame("subscribed", f.sent("subscribe")[1])
        assertEquals(5, f.sent("subscribe").size)
        assertEquals(1, f.sent("subscribe").count { it == denied })
    }

    @Test fun cancelingAnInFlightRoomKeepsItsSlotUntilTheReplyAndRejectsALateAdmission() {
        val f = Fixture()
        repeat(5) { f.client.subscribe("live-audience:room-$it") }
        f.tcpOpen(); f.frame("connected")
        val canceled = f.sent("subscribe").first()
        f.client.unsubscribe(canceled)
        assertEquals(3, f.sent("subscribe").size)
        f.frame("subscribed", canceled)
        assertEquals(4, f.sent("subscribe").size)
        assertEquals("Late authorization must not leave an unwanted room joined", 2,
            f.sent("unsubscribe").count { it == canceled })
    }

    @Test fun tokenRotationReplaysDesiredChannelsAndOldRepliesCannotReleaseNewSlots() {
        val f = Fixture()
        repeat(5) { f.client.subscribe("live-audience:room-$it") }
        f.tcpOpen(); f.frame("connected")
        val old = f.sent("subscribe").toList()
        f.token = "fixture-refreshed-token"; f.client.updateAccessToken(f.token)
        f.tcpOpen()
        assertTrue(f.sockets.last().sent.isEmpty())
        f.frame("connected")
        assertEquals(3, f.sent("subscribe").size)
        old.forEach { f.frame("subscribed", it, index = 0) }
        assertEquals(3, f.sent("subscribe").size)
        f.frame("subscribed", f.sent("subscribe").first())
        assertEquals(4, f.sent("subscribe").size)
    }

    @Test fun channelNamesAreSerializedAsDataAndOversizedChannelsNeverConsumeSlots() {
        val f = Fixture()
        val channel = "live-audience:quote\"and\\slash"
        f.client.subscribe(channel)
        f.client.subscribe("x".repeat(257))
        f.tcpOpen(); f.frame("connected")
        assertEquals(setOf("user:a", channel), f.sent("subscribe").toSet())
    }
}
