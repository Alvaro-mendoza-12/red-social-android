package conectate.com.presentation.messages

import android.app.Application
import android.net.Uri
import android.util.AtomicFile
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.util.IncomingSharedItem
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ShareBatchJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun diskSpace() { ShadowStatFs.registerStats(temporary.root, 1_000_000, 900_000, 900_000) }
    private val chat = ShareDestination("chat", ShareDestinationType.CHAT, "Friend")
    private fun manifest(folder: File) = folder.listFiles()!!.single { it.name.endsWith(".json") }
    private val group = ShareDestination("group", ShareDestinationType.GROUP, "Group")
    private fun item(target: ShareDestination = chat, content: String = "original") =
        ShareBatchItem("text", target, ShareOutgoingContent.Text(content))

    @Test fun aReconstructedBatchKeepsTheExactCommandAndSkipsTheAcceptedRecipient() = runBlocking {
        val folder = temporary.newFolder(); val calls = mutableListOf<ShareBatchItem>()
        val original = listOf(item(), item(group))
        val first = ShareBatchDelivery(0, ShareBatchJournal(folder))
        first.send("request", "a", original, {}, { _, _ -> }) { row, _, _ ->
            calls += row
            if (row.destination == group) Result.failure(IOException("Offline")) else Result.success(row.destination)
        }
        val next = ShareBatchDelivery(0, ShareBatchJournal(folder))
        val result = next.send("request", "a", listOf(item(chat, "EDITED")), {}, { _, _ -> }) { row, _, _ ->
            calls += row; Result.success(row.destination)
        }
        assertEquals(listOf(original[0], original[1], original[1]), calls)
        assertEquals(2, result.accepted)
        assertTrue(ShareBatchJournal(folder).load("request", "a", {})!!.closed)
    }

    @Test fun lostAcknowledgementReusesTheSameIdAcrossStoreAndBatchInstances() = runBlocking {
        val folder = temporary.newFolder(); val ids = mutableListOf<String>(); val server = mutableMapOf<String, ShareBatchItem>()
        repeat(2) { attempt ->
            ShareBatchDelivery(0, ShareBatchJournal(folder)).send("r", "a", listOf(item(content = if (attempt == 0) "first" else "changed")), {}, { _, _ -> }) { row, id, _ ->
                ids += id; server.putIfAbsent(id, row)
                if (attempt == 0) Result.failure(IOException("Response lost")) else Result.success(row.destination)
            }
        }
        assertEquals(ids.first(), ids.last())
        assertEquals(1, server.size)
        assertEquals("first", (server.values.single().content as ShareOutgoingContent.Text).content)
    }

    @Test fun anotherAccountCannotReadOrReplaceTheSameRequestsCommand() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        journal.prepare("r", "a", listOf(item()), {})
        assertTrue(runCatching { journal.load("r", "b", {}) }.exceptionOrNull() is ShareBatchOwnerMismatch)
        assertTrue(runCatching { journal.prepare("r", "b", listOf(item(group)), {}) }.exceptionOrNull() is ShareBatchOwnerMismatch)
        assertEquals(listOf(item()), journal.load("r", "a", {})!!.plan)
        assertEquals(1, folder.listFiles()!!.count { it.name.endsWith(".json") })
    }

    @Test fun acceptedQueueHandoffIsSavedBeforeAUiCallbackCancelsDelivery() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        val original = temporary.newFile("queued.pptx").apply { writeBytes(ByteArray(100) { 1 }) }
        val attachment = ShareBatchItem("file", chat, ShareOutgoingContent.Attachment(IncomingSharedItem(
            Uri.fromFile(original), "application/octet-stream", "file.pptx", 100), "Caption"))
        val error = runCatching {
            ShareBatchDelivery(0, journal).send("r", "a", listOf(attachment), {}, { _, _ -> }, onQueued = {
                assertTrue(ShareBatchJournal(folder).load("r", "a", {})!!.closed)
                throw CancellationException("The Activity closed after durable handoff")
            }) { row, _, queued -> queued(); Result.success(row.destination) }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        var sent = false
        ShareBatchDelivery(0, ShareBatchJournal(folder)).send("r", "a", listOf(attachment), {}, { _, _ -> }) { row, _, _ ->
            sent = true; Result.success(row.destination)
        }
        assertFalse(sent)
    }

    @Test fun textQueueSignalsDoNotPretendToBeAnAcknowledgement() = runBlocking {
        val journal = ShareBatchJournal(temporary.newFolder())
        ShareBatchDelivery(0, journal).send("r", "a", listOf(item()), {}, { _, _ -> }) { _, _, queued ->
            queued(); Result.failure(IOException("Still pending"))
        }
        val saved = journal.load("r", "a", {})!!
        assertFalse(saved.closed); assertTrue(saved.receipts.isEmpty())
    }

    @Test fun failedLocalStoragePreventsTheFirstTransportCall() = runBlocking {
        var sent = false
        val result = runCatching {
            ShareBatchDelivery(0, ShareBatchJournal(temporary.newFile())).send("r", "a", listOf(item()), {}, { _, _ -> }) { row, _, _ ->
                sent = true; Result.success(row.destination)
            }
        }
        assertTrue(result.isFailure); assertFalse(sent)
    }

    @Test fun corruptedCommandsCannotFallBackToAnEditableNewRequest() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        journal.prepare("r", "a", listOf(item()), {})
        manifest(folder).writeText("broken JSON")
        var sent = false
        assertTrue(runCatching {
            ShareBatchDelivery(0, ShareBatchJournal(folder)).send("r", "a", listOf(item(group)), {}, { _, _ -> }) { row, _, _ ->
                sent = true; Result.success(row.destination)
            }
        }.isFailure)
        assertFalse(sent)
        assertEquals("broken JSON", manifest(folder).readText())
    }

    @Test fun interruptedAtomicReplacementRestoresTheLastCompleteCommand() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        journal.prepare("r", "a", listOf(item()), {})
        AtomicFile(manifest(folder)).startWrite().apply { write("partial".toByteArray()); close() }
        assertEquals(listOf(item()), ShareBatchJournal(folder).load("r", "a", {})!!.plan)
    }

    @Test fun closingCompactsTheCommandAndCannotBeUndoneByALateReceipt() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        journal.prepare("r", "a", listOf(item(content = "PRIVATE BODY")), {})
        journal.close("r", "a", {})
        journal.accepted("r", "a", item().key, chat, {})
        val saved = journal.load("r", "a", {})!!
        assertTrue(saved.closed); assertTrue(saved.plan.isEmpty()); assertTrue(saved.receipts.isEmpty())
        assertFalse(manifest(folder).readText().contains("PRIVATE BODY"))
    }

    @Test fun aClosedActionCannotDispatchAfterAProcessRestore() = runBlocking {
        val folder = temporary.newFolder(); ShareBatchJournal(folder).close("r", "a", {})
        var sent = false
        ShareBatchDelivery(0, ShareBatchJournal(folder)).send("r", "a", listOf(item()), {}, { _, _ -> }) { row, _, _ ->
            sent = true; Result.success(row.destination)
        }
        assertFalse(sent)
    }

    @Test fun accountChangeDuringPersistenceNeverUnlocksAnotherAccountsCommand() = runBlocking {
        val journal = ShareBatchJournal(temporary.newFolder()); var owner = "a"; var checks = 0
        val guard = AuthSessionMutationGuard(); val account = guard.captureAccountLease { owner }
        val error = runCatching { journal.prepare("r", "a", listOf(item()), {
            if (++checks == 3) guard.invalidate { owner = "b" }
            account.requireCurrent()
        }) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertNull(journal.load("r", "b", {}))
    }

    @Test fun concurrentPreparationOfOneRequestNeverReplacesItsFirstCommand() = runBlocking {
        val folder = temporary.newFolder(); val start = CompletableDeferred<Unit>()
        val saved = coroutineScope { (0..7).map { index -> async(Dispatchers.IO) {
            start.await(); ShareBatchJournal(folder).prepare("r", "a", listOf(item(content = "$index")), {})
        } }.also { start.complete(Unit) }.awaitAll() }
        assertEquals(1, saved.map { it.plan }.distinct().size)
    }

    @Test fun anOversizedPlanFailsBeforeDispatchAndLeavesNoCommand() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        assertTrue(runCatching { journal.prepare("r", "a", listOf(item(content = "x".repeat(2 * 1024 * 1024))), {}) }.isFailure)
        assertNull(journal.load("r", "a", {}))
    }

    @Test fun diskReadsAreBoundedBeforeParsingAnOversizedRecord() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        journal.prepare("r", "a", listOf(item()), {})
        manifest(folder).writeBytes(ByteArray(2 * 1024 * 1024 + 1))
        assertTrue(runCatching { journal.load("r", "a", {}) }.isFailure)
    }

    @Test fun attachmentsAndForwardsRoundTripWithoutLosingMetadata() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        val file = temporary.newFile("original.pptx").apply { writeBytes(ByteArray(123456) { (it % 127).toByte() }) }
        val attachment = ShareBatchItem("file", chat, ShareOutgoingContent.Attachment(IncomingSharedItem(
            Uri.fromFile(file), "application/vnd.openxmlformats-officedocument.presentationml.presentation", "Original.pptx", 123456), "Original caption"))
        val forward = ShareBatchItem("forward:source", group, ShareOutgoingContent.Forward(ForwardMessagePayload(
            "source", "author", "original", "document", "https://server.example/file.pptx", 1234, false, "delivered")))
        val plan = listOf(attachment, forward)
        journal.prepare("r", "a", plan, {})
        val restored = ShareBatchJournal(folder).load("r", "a", {})!!.plan
        assertEquals(forward, restored[1])
        val restoredFile = restored[0].content as ShareOutgoingContent.Attachment
        val originalFile = attachment.content as ShareOutgoingContent.Attachment
        assertEquals(originalFile.content, restoredFile.content)
        assertEquals(originalFile.item.copy(uri = restoredFile.item.uri), restoredFile.item)
        assertNotEquals(originalFile.item.uri, restoredFile.item.uri)
        val expected = file.readBytes(); assertTrue(file.delete())
        assertArrayEquals(expected, File(restoredFile.item.uri.path!!).readBytes())
    }

    @Test fun restartingTheApplicationDiscoversOnlyTheCurrentAccountsPendingCommands() = runBlocking {
        val folder = temporary.newFolder()
        ShareBatchJournal(folder).prepare("a-first", "a", listOf(item()), {})
        ShareBatchJournal(folder).prepare("b-first", "b", listOf(item()), {})
        ShareBatchJournal(folder).prepare("a-second", "a", listOf(item()), {})
        val reopened = ShareBatchJournal(folder)
        assertEquals(listOf("a-first", "a-second"), reopened.pending("a", {}))
        assertEquals(listOf("b-first"), reopened.pending("b", {}))
        reopened.close("a-first", "a", {})
        assertEquals(listOf("a-second"), reopened.pending("a", {}))
    }

    @Test fun losingACommandFileDoesNotTurnItsIndexedRequestIntoANewEditableDraft() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        journal.prepare("r", "a", listOf(item()), {})
        assertTrue(manifest(folder).delete())
        assertTrue(runCatching { journal.load("r", "a", {}) }.isFailure)
        assertTrue(runCatching { journal.prepare("r", "b", listOf(item()), {}) }.exceptionOrNull() is ShareBatchOwnerMismatch)
        assertTrue(runCatching { journal.close("r", "b", {}) }.exceptionOrNull() is ShareBatchOwnerMismatch)
    }

    @Test fun anUnreadableIndexPreventsTransportRatherThanLosingRecoveryOfAnInternalShare() = runBlocking {
        val folder = temporary.newFolder(); File(folder, "pending.index").writeText("broken index")
        var sent = false
        assertTrue(runCatching {
            ShareBatchDelivery(0, ShareBatchJournal(folder)).send("r", "a", listOf(item()), {}, { _, _ -> }) { row, _, _ ->
                sent = true; Result.success(row.destination)
            }
        }.isFailure)
        assertFalse(sent)
    }

    @Test fun closingRemovesOnlyTheBatchesCopiesAndPreservesTheOriginalFiles() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        val file = temporary.newFile("keep-original.pptx").apply { writeText("original bytes") }
        val attachment = ShareBatchItem("file", chat, ShareOutgoingContent.Attachment(IncomingSharedItem(
            Uri.fromFile(file), "application/octet-stream", file.name), "Caption"))
        val saved = journal.prepare("r", "a", listOf(attachment), {})
        val owned = File((saved.plan.single().content as ShareOutgoingContent.Attachment).item.uri.path!!)
        assertTrue(owned.exists())
        journal.close("r", "a", {})
        assertFalse(owned.exists()); assertEquals("original bytes", file.readText())
    }

    @Test fun selectingTheSameAttachmentForSeveralRecipientsKeepsOnePrivateCopy() = runBlocking {
        val folder = temporary.newFolder(); val journal = ShareBatchJournal(folder)
        val file = temporary.newFile("one-copy.pptx").apply { writeText("same bytes") }
        val attachment = ShareBatchItem("file", chat, ShareOutgoingContent.Attachment(IncomingSharedItem(
            Uri.fromFile(file), "application/octet-stream", file.name), "Caption"))
        val plan = journal.prepare("r", "a", listOf(attachment, attachment.copy(destination = group)), {}).plan
        assertEquals(1, plan.map { (it.content as ShareOutgoingContent.Attachment).item.uri }.toSet().size)
    }
}
