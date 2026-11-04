package conectate.com.presentation.messages

import android.net.Uri
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.util.IncomingSharedItem
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Real Android replacement, fsync and recovery, including the modern .new protocol. */
@RunWith(AndroidJUnit4::class)
class ShareBatchJournalDeviceTest {
    @get:Rule val temporary = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
    private val chat = ShareDestination("chat", ShareDestinationType.CHAT, "Friend")
    private val group = ShareDestination("group", ShareDestinationType.GROUP, "Group")
    private fun item(destination: ShareDestination = chat, text: String = "original") =
        ShareBatchItem("text", destination, ShareOutgoingContent.Text(text))

    @Test fun replacingReceiptsAndClosingSurviveReopeningTheJournal() = runBlocking {
        val folder = temporary.newFolder()
        val original = listOf(item(), item(group))
        val journal = ShareBatchJournal(folder)
        journal.prepare("request", "owner", original, {})
        journal.accepted("request", "owner", original[0].key, chat, {})
        val reopened = ShareBatchJournal(folder)
        val saved = reopened.prepare("request", "owner", listOf(item(text = "edited")), {})
        assertEquals(original, saved.plan)
        assertEquals(mapOf(original[0].key to chat), saved.receipts)
        reopened.accepted("request", "owner", original[1].key, group, {})
        val closed = ShareBatchJournal(folder).load("request", "owner", {})!!
        assertTrue(closed.closed)
        assertTrue(closed.plan.isEmpty())
        assertTrue(closed.receipts.isEmpty())
        assertTrue(reopened.pending("owner", {}).isEmpty())
        reopened.accepted("request", "owner", original[0].key, chat, {})
        assertTrue(reopened.load("request", "owner", {})!!.closed)
    }

    @Test fun replacingTheIndexKeepsEachAccountsOtherPendingCommands() = runBlocking {
        val folder = temporary.newFolder()
        val journal = ShareBatchJournal(folder)
        journal.prepare("a-first", "a", listOf(item()), {})
        journal.prepare("b-first", "b", listOf(item()), {})
        journal.prepare("a-second", "a", listOf(item()), {})
        val reopened = ShareBatchJournal(folder)
        assertEquals(listOf("a-first", "a-second"), reopened.pending("a", {}))
        assertEquals(listOf("b-first"), reopened.pending("b", {}))
        reopened.close("a-first", "a", {})
        assertEquals(listOf("a-second"), reopened.pending("a", {}))
        assertEquals(listOf("b-first"), reopened.pending("b", {}))
        assertTrue(runCatching { reopened.load("a-second", "b", {}) }.exceptionOrNull() is ShareBatchOwnerMismatch)
    }

    @Test fun queuedAttachmentIsDurableBeforeTheScreenClosesAndCannotSendAgain() = runBlocking {
        val folder = temporary.newFolder()
        val source = temporary.newFile("slides.pptx").apply { writeBytes(ByteArray(100) { 7 }) }
        val attachment = ShareBatchItem("file", chat, ShareOutgoingContent.Attachment(
            IncomingSharedItem(Uri.fromFile(source), "application/octet-stream", source.name, source.length()), "Caption",
        ))
        val error = runCatching {
            ShareBatchDelivery(0, ShareBatchJournal(folder)).send(
                "request", "owner", listOf(attachment), {}, { _, _ -> },
                onQueued = {
                    assertTrue(ShareBatchJournal(folder).load("request", "owner", {})!!.closed)
                    throw CancellationException("Screen closed after durable handoff")
                },
            ) { row, _, queued ->
                queued()
                Result.success(row.destination)
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        var sentAgain = false
        val reopened = ShareBatchJournal(folder)
        ShareBatchDelivery(0, reopened).send("request", "owner", listOf(attachment), {}, { _, _ -> }) { row, _, _ ->
            sentAgain = true
            Result.success(row.destination)
        }
        assertFalse(sentAgain)
        assertTrue(reopened.pending("owner", {}).isEmpty())
        assertArrayEquals(ByteArray(100) { 7 }, source.readBytes())
    }

    @Test fun interruptedModernReplacementRestoresThePreviousCompleteCommand() = runBlocking {
        val folder = temporary.newFolder()
        val original = listOf(item())
        ShareBatchJournal(folder).prepare("request", "owner", original, {})
        val record = folder.listFiles()!!.single { it.name.endsWith(".json") }
        AtomicFile(record).startWrite().apply { write("partial".toByteArray()); close() }
        assertEquals(original, ShareBatchJournal(folder).load("request", "owner", {})!!.plan)
    }
}
