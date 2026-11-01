package conectate.com.presentation.messages

import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareBatchDeliveryTest {
    private val chat = ShareDestination("chat", ShareDestinationType.CHAT, "Amiga")
    private val group = ShareDestination("group", ShareDestinationType.GROUP, "Grupo")
    private val community = ShareDestination("community", ShareDestinationType.COMMUNITY, "Comunidad")
    private fun item(target: ShareDestination, text: String = "original", key: String = "content") =
        ShareBatchItem(key, target, ShareOutgoingContent.Text(text))
    private suspend fun send(batch: ShareBatchDelivery, items: List<ShareBatchItem>, request: String = "request",
        account: String = "a", guard: () -> Unit = {}, progress: (Int, Int) -> Unit = { _, _ -> },
        transport: suspend (ShareBatchItem, String, suspend () -> Unit) -> Result<ShareDestination>) =
        batch.send(request, account, items, guard, progress, deliver = transport)

    @Test fun theSameOriginalReachesAllThreeConversationTypes() = runTest {
        val received = mutableListOf<Pair<String, ShareOutgoingContent>>()
        val result = send(ShareBatchDelivery(0), listOf(chat, group, community).map { item(it) }) { entry, _, _ ->
            received += entry.destination.key to entry.content; Result.success(entry.destination)
        }
        assertEquals(3, result.accepted)
        assertEquals(setOf(chat.key, group.key, community.key), received.map { it.first }.toSet())
        assertEquals(setOf(ShareOutgoingContent.Text("original")), received.map { it.second }.toSet())
    }

    @Test fun partialRetryOnlySendsPendingItemsAndRetainsProgress() = runTest {
        val batch = ShareBatchDelivery(0); var offline = true
        val calls = mutableListOf<String>(); val progress = mutableListOf<Int>()
        val transport: suspend (ShareBatchItem, String, suspend () -> Unit) -> Result<ShareDestination> = { entry, _, _ ->
            calls += entry.destination.key
            if (entry.destination == group && offline) Result.failure(java.io.IOException()) else Result.success(entry.destination)
        }
        assertEquals(1, send(batch, listOf(item(chat), item(group)), transport = transport).accepted)
        offline = false
        assertEquals(2, send(batch, listOf(item(chat), item(group)), progress = { completed, _ -> progress += completed }, transport = transport).accepted)
        assertEquals(listOf(chat.key, group.key, group.key), calls)
        assertEquals(listOf(1, 2), progress)
    }

    @Test fun aRetryCannotChangeTheFrozenContentTargetsOrSourceOrder() = runTest {
        val batch = ShareBatchDelivery(0); val attempts = mutableListOf<ShareBatchItem>()
        send(batch, listOf(item(chat, "first"))) { entry, _, _ -> attempts += entry; Result.failure(java.io.IOException()) }
        val result = send(batch, listOf(item(group, "edited"))) { entry, _, _ -> attempts += entry; Result.success(entry.destination) }
        assertEquals(listOf(item(chat, "first"), item(chat, "first")), attempts)
        assertEquals(chat, result.lastDestination)
        assertEquals(listOf(item(chat, "first")), batch.frozenPlan("request", "a"))
        assertTrue(batch.frozenPlan("request", "b").isEmpty())
    }

    @Test fun aLostResponseReusesTheSameIdempotencyKey() = runTest {
        val batch = ShareBatchDelivery(0); val ids = mutableListOf<String>(); var attempts = 0
        repeat(2) {
            send(batch, listOf(item(group))) { entry, id, _ ->
                ids += id
                if (++attempts == 1) Result.failure(java.io.IOException("Lost response")) else Result.success(entry.destination)
            }
        }
        assertEquals(ids[0], ids[1])
    }

    @Test fun idsAreSeparatedByAccountRequestTargetAndSourceItem() = runTest {
        val ids = mutableSetOf<String>()
        for ((request, account) in listOf("r" to "a", "other" to "a", "r" to "b")) {
            send(ShareBatchDelivery(0), listOf(item(chat, key = "one"), item(chat, key = "two"), item(group, key = "one")), request, account) { entry, id, _ ->
                assertTrue(ids.add(id)); Result.success(entry.destination)
            }
        }
        assertEquals(9, ids.size)
    }

    @Test fun duplicateSelectionDoesNotDuplicateTheSend() = runTest {
        var calls = 0
        val result = send(ShareBatchDelivery(0), listOf(item(chat), item(chat))) { entry, _, _ -> calls++; Result.success(entry.destination) }
        assertEquals(1, calls); assertEquals(1, result.total)
    }

    @Test fun wrappedCancellationCannotBecomeSuccessOrContinueToAnotherRecipient() = runTest {
        var calls = 0
        val error = runCatching {
            send(ShareBatchDelivery(0), listOf(item(chat), item(group))) { _, _, _ -> calls++; Result.failure(CancellationException("Leaving")) }
        }.exceptionOrNull()
        assertTrue(error is CancellationException); assertEquals(1, calls)
    }

    @Test fun switchingAccountsBetweenRecipientsStopsBeforeTheSecondSend() = runTest {
        val auth = AuthSessionMutationGuard(); var account = "a"
        val lease = auth.captureAccountLease { account }; var calls = 0
        val error = runCatching {
            send(ShareBatchDelivery(0), listOf(item(chat), item(group)), guard = lease::requireCurrent) { entry, _, _ ->
                calls++; auth.invalidate { account = "b" }; Result.success(entry.destination)
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException); assertEquals(1, calls)
    }

    @Test fun resettingDuringAnUncancellableResponseCannotPolluteTheNextRequestsReceipts() = runTest {
        val batch = ShareBatchDelivery(0); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = async {
            send(batch, listOf(item(chat))) { entry, _, _ ->
                withContext(NonCancellable) { entered.complete(Unit); release.await() }; Result.success(entry.destination)
            }
        }
        runCurrent(); entered.await(); batch.reset()
        val next = send(batch, listOf(item(group)), request = "next") { entry, _, _ -> Result.success(entry.destination) }
        release.complete(Unit); runCurrent()
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, next.accepted); assertEquals(group, next.lastDestination)
        assertEquals(listOf(item(group)), batch.frozenPlan("next", "a"))
    }

    @Test fun anotherUserActionCanDeliberatelyShareTheSameContentAgain() = runTest {
        val batch = ShareBatchDelivery(0); var sends = 0
        repeat(2) { number -> send(batch, listOf(item(chat)), request = "r$number") { entry, _, _ -> sends++; Result.success(entry.destination) } }
        assertEquals(2, sends)
    }

    @Test fun aResolvedConversationBecomesTheOpenDestination() = runTest {
        val person = chat.copy(id = "person:friend")
        val result = send(ShareBatchDelivery(0), listOf(item(person))) { _, _, _ -> Result.success(chat) }
        assertEquals(chat, result.lastDestination)
    }

    @Test fun forwardedPayloadIsRetainedExactlyAcrossRetries() = runTest {
        val source = ForwardMessagePayload("source", "friend", "metadata", "document", "https://media/file", 1, false)
        val original = ShareBatchItem("source", group, ShareOutgoingContent.Forward(source))
        val batch = ShareBatchDelivery(0)
        send(batch, listOf(original)) { _, _, _ -> Result.failure(java.io.IOException()) }
        send(batch, listOf(original.copy(content = ShareOutgoingContent.Text("changed")))) { entry, _, _ ->
            assertEquals(original, entry); Result.success(entry.destination)
        }
    }

    @Test fun aSingleDurableQueueSignalIsRetainedWithoutAnActivityCallback() = runTest {
        var queued: ShareDestination? = null
        ShareBatchDelivery(0).send("r", "a", listOf(item(chat)), {}, { _, _ -> }, onQueued = { queued = it }) { entry, _, callback ->
            callback(); Result.success(entry.destination)
        }
        assertEquals(chat, queued)
    }

    @Test fun aBatchDoesNotSignalThatItCanCloseAfterItsFirstQueuedItem() = runTest {
        var queued = false
        ShareBatchDelivery(0).send("r", "a", listOf(item(chat), item(group)), {}, { _, _ -> }, onQueued = { queued = true }) { entry, _, callback ->
            callback(); Result.success(entry.destination)
        }
        assertFalse(queued)
    }

    @Test fun queuedDirectContentIsNotACompletedInternalShareAndRetryUsesTheSameId() = runTest {
        val batch = ShareBatchDelivery(0); val ids = mutableListOf<String>(); var pending = true
        val transport: suspend (ShareBatchItem, String, suspend () -> Unit) -> Result<ShareDestination> = { entry, id, _ ->
            ids += id
            confirmedDirectContentShare(conectate.com.data.model.Message(id = id, status = if (pending) "sending" else "sent")).map { entry.destination }
        }
        assertEquals(0, send(batch, listOf(item(chat)), transport = transport).accepted)
        pending = false
        assertEquals(1, send(batch, listOf(item(chat)), transport = transport).accepted)
        assertEquals(ids[0], ids[1])
    }

    @Test fun directContentRequiresAnAcknowledgedAndNonDeletedState() {
        for (state in listOf("sent", "delivered", "read", " SENT ")) assertTrue(confirmedDirectContentShare(conectate.com.data.model.Message(status = state)).isSuccess)
        for (state in listOf("sending", "pending", "failed", "unknown")) assertTrue(confirmedDirectContentShare(conectate.com.data.model.Message(status = state)).isFailure)
        assertTrue(confirmedDirectContentShare(conectate.com.data.model.Message(status = "sent", deleted_for_all = true)).isFailure)
    }
}
