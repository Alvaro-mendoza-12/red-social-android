package conectate.com.presentation.messages

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class InternalContentShareSenderTest {
    private val chat = ShareDestination("chat", ShareDestinationType.CHAT, "Amiga")
    private val group = ShareDestination("group", ShareDestinationType.GROUP, "Grupo")
    private val community = ShareDestination("community", ShareDestinationType.COMMUNITY, "Comunidad")

    @Test fun theSameOriginalIsActuallySentToAllThreeTypesOfConversation() = runTest {
        val received = mutableListOf<Pair<String, String>>()
        val sender = InternalContentShareSender()
        val result = sender.send("request", "owner", listOf(chat, group, community), "https://red-social-android.vercel.app/post/original",
            { "owner" }, { null }, { target, text, _ -> received += target.key to text; Result.success(Unit) })
        assertEquals(3, result.sent)
        assertEquals(setOf(chat.key, group.key, community.key), received.map { it.first }.toSet())
        assertEquals(1, received.map { it.second }.toSet().size)
    }

    @Test fun aPartialRetryDoesNotRepeatAlreadyCompletedDestinations() = runTest {
        val sender = InternalContentShareSender()
        val calls = mutableListOf<String>()
        var fail = true
        val send: suspend (ShareDestination, String, String) -> Result<Unit> = { target, _, _ ->
            calls += target.key
            if (target == group && fail) Result.failure(Exception("offline")) else Result.success(Unit)
        }
        assertEquals(1, sender.send("r", "a", listOf(chat, group), "post", { "a" }, { null }, send).sent)
        fail = false
        assertEquals(2, sender.send("r", "a", listOf(chat, group), "post", { "a" }, { null }, send).sent)
        assertEquals(listOf(chat.key, group.key, group.key), calls)
    }

    @Test fun aPersonWithoutAConversationIsResolvedBeforeSending() = runTest {
        var destination: ShareDestination? = null
        val result = InternalContentShareSender().send("r", "a", listOf(chat.copy(id = "person:new-user")), "post",
            { "a" }, { id -> assertEquals("new-user", id); chat }, { target, _, _ -> destination = target; Result.success(Unit) })
        assertEquals(chat, destination)
        assertEquals(chat, result.lastDestination)
    }

    @Test fun switchingAccountsDuringConversationResolutionStopsTheSend() = runTest {
        var account = "a"; var sent = false
        try {
            InternalContentShareSender().send("r", "a", listOf(chat.copy(id = "person:user")), "post", { account },
                { account = "b"; chat }, { _, _, _ -> sent = true; Result.success(Unit) })
            fail("Switched account was accepted")
        } catch (_: IllegalStateException) { }
        assertFalse(sent)
    }

    @Test fun cancellationCannotTurnIntoASuccessOrContinueToOtherRecipients() = runTest {
        var attempts = 0
        try {
            InternalContentShareSender().send("r", "a", listOf(chat, group), "post", { "a" }, { null }, { _, _, _ ->
                attempts++; Result.failure(CancellationException("Leaving"))
            })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertEquals(1, attempts)
    }

    @Test fun anotherShareCanSendTheSamePostAgainDeliberately() = runTest {
        val sender = InternalContentShareSender(); var sent = 0
        repeat(2) { request -> sender.send("r$request", "a", listOf(chat), "same post", { "a" }, { null },
            { _, _, _ -> sent++; Result.success(Unit) }) }
        assertEquals(2, sent)
    }
    @Test fun aLostServerResponseRetainsTheSameIdempotencyKeyOnRetry() = runTest {
        val sender = InternalContentShareSender(); val ids = mutableListOf<String>()
        repeat(2) { attempt ->
            sender.send("one-user-action", "account-a", listOf(group), "same original", { "account-a" }, { null },
                { _, _, id -> ids += id; if (attempt == 0) Result.failure(Exception("Response lost")) else Result.success(Unit) })
        }
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
    }
}
