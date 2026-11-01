package conectate.com.util

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PendingOutgoingMediaStoreTest {
    private val context get() = RuntimeEnvironment.getApplication() as Application
    private val files = mutableListOf<File>()
    private val copies = mutableListOf<Uri>()
    private val incoming = mutableListOf<IncomingSharePayload>()
    @Before fun availableSpace() { ShadowStatFs.registerStats(context.filesDir, 1_000_000, 900_000, 900_000) }
    private fun source(bytes: ByteArray, extension: String = "bin"): File =
        File(context.cacheDir, "audit-${UUID.randomUUID()}.$extension").also { it.writeBytes(bytes); files += it }
    private suspend fun persist(id: String, file: File, account: String = "a", namespace: String = "group") =
        PendingOutgoingMediaStore.persist(context, account, namespace, id, Uri.fromFile(file), "document").also {
            it.getOrNull()?.uri?.let(copies::add)
        }
    @After fun cleanup() {
        copies.forEach { PendingOutgoingMediaStore.delete(context, it) }
        incoming.forEach { PendingOutgoingMediaStore.deleteIncomingShare(context, it) }
        files.forEach { it.delete() }
    }

    @Test fun aRetryReusesItsDurableCopyAfterTheOriginalDisappears() = runBlocking {
        val file = source("document original".toByteArray())
        val id = UUID.randomUUID().toString()
        val first = persist(id, file).getOrThrow()
        assertTrue(file.delete())
        val retry = persist(id, file).getOrThrow()
        assertEquals(first.uri, retry.uri)
        assertEquals("document original", File(retry.uri.path!!).readText())
    }

    @Test fun anotherPreparationCannotReplaceADurableMessageWithDifferentBytes() = runBlocking {
        val id = UUID.randomUUID().toString()
        val first = persist(id, source("first".toByteArray())).getOrThrow()
        val retry = persist(id, source("changed".toByteArray())).getOrThrow()
        assertEquals(first.uri, retry.uri)
        assertEquals("first", File(first.uri.path!!).readText())
    }

    @Test fun simultaneousPreparationsOfTheSameIdPublishOneCompleteCopy() = runBlocking {
        val id = UUID.randomUUID().toString()
        val first = source(ByteArray(512 * 1024) { 1 })
        val second = source(ByteArray(512 * 1024) { 2 })
        val start = CompletableDeferred<Unit>()
        val results = coroutineScope {
            val jobs = (0..7).map { index -> async(Dispatchers.IO) {
                start.await()
                PendingOutgoingMediaStore.persist(context, "a", "group", id, Uri.fromFile(if (index % 2 == 0) first else second), "document").getOrThrow()
            } }
            start.complete(Unit); jobs.awaitAll()
        }
        copies += results.map { it.uri }
        assertEquals(1, results.map { it.uri }.toSet().size)
        val bytes = File(results.first().uri.path!!).readBytes()
        assertEquals(512 * 1024, bytes.size)
        assertTrue(bytes.all { it == bytes.first() })
        assertTrue(bytes.first() in listOf(1.toByte(), 2.toByte()))
        val directory = File(results.first().uri.path!!).parentFile!!
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(id) && it.name.endsWith(".part") })
    }

    @Test fun copiesWithTheSameMessageIdRemainSeparatedByAccountAndConversationKind() = runBlocking {
        val id = UUID.randomUUID().toString()
        val file = source("shared document".toByteArray())
        val results = listOf(persist(id, file, "a", "group"), persist(id, file, "b", "group"), persist(id, file, "a", "community")).map { it.getOrThrow() }
        assertEquals(3, results.map { it.uri }.toSet().size)
        assertTrue(results.all { File(it.uri.path!!).readText() == "shared document" })
    }

    @Test fun anEmptySourceCannotLeaveAnAcceptedOrPartialCopy() = runBlocking {
        val id = UUID.randomUUID().toString()
        assertTrue(persist(id, source(byteArrayOf())).isFailure)
        val directory = File(context.filesDir, "pending-collection-media/a/group")
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(id) })
    }

    @Test fun anIncomingCaptureCanBeRestoredWithoutItsOriginalSource() = runBlocking {
        val file = source("presentation bytes".toByteArray(), "pptx")
        val payload = IncomingSharePayload(items = listOf(IncomingSharedItem(Uri.fromFile(file), "application/vnd.openxmlformats-officedocument.presentationml.presentation", "slides.pptx")))
        incoming += payload
        val captured = PendingOutgoingMediaStore.persistIncomingShare(context, payload).getOrThrow()
        assertTrue(file.delete())
        val restored = PendingOutgoingMediaStore.persistIncomingShare(context, captured).getOrThrow()
        assertEquals(1, restored.items.size)
        assertEquals(captured.items.first().uri, restored.items.first().uri)
        assertEquals("slides.pptx", restored.items.first().displayName)
        assertEquals("presentation bytes", File(restored.items.first().uri.path!!).readText())
    }

    @Test fun anAccountChangeDuringCopyPropagatesCancellationAndRemovesOnlyItsPartialFile() = runBlocking {
        val id = UUID.randomUUID().toString()
        val file = source(ByteArray(256 * 1024) { 3 })
        var checks = 0
        val error = runCatching {
            PendingOutgoingMediaStore.persist(context, "a", "chat", id, Uri.fromFile(file), "document") {
                if (++checks == 4) throw CancellationException("Session changed during copy")
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(currentCoroutineContext().isActive)
        val directory = File(context.filesDir, "pending-collection-media/a/chat")
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(id) })
        assertTrue(file.exists())
    }

    @Test fun insufficientStorageIsStillRejectedForNewCopies() = runBlocking {
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1, 1)
        val result = persist(UUID.randomUUID().toString(), source(ByteArray(1024) { 1 }))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("espacio"))
    }

    @Test fun insufficientStorageDoesNotInvalidateAnExistingDurableCopy() = runBlocking {
        val id = UUID.randomUUID().toString()
        val file = source("already safe".toByteArray())
        val first = persist(id, file).getOrThrow()
        file.delete()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1, 1)
        assertEquals(first.uri, persist(id, file).getOrThrow().uri)
    }

    @Test fun aNewIncomingRequestOwnsItsCopyBeforeThePreviousRequestIsConsumed() = runBlocking {
        val file = source("keep for next request".toByteArray())
        val first = IncomingSharePayload(items = listOf(IncomingSharedItem(Uri.fromFile(file), "application/octet-stream", "file.bin")))
        incoming += first
        val captured = PendingOutgoingMediaStore.persistIncomingShare(context, first).getOrThrow()
        assertEquals(1, captured.items.size)
        val next = captured.copy(id = UUID.randomUUID().toString())
        incoming += next
        val second = PendingOutgoingMediaStore.persistIncomingShare(context, next).getOrThrow()
        assertEquals(1, second.items.size)
        assertNotEquals(captured.items.first().uri, second.items.first().uri)
        PendingOutgoingMediaStore.deleteIncomingShare(context, captured)
        assertFalse(File(captured.items.first().uri.path!!).exists())
        assertEquals("keep for next request", File(second.items.first().uri.path!!).readText())
    }
}
