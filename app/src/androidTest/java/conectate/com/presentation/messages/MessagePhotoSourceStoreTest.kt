package conectate.com.presentation.messages

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Private files and injected local streams; no accounts, providers or network. */
@RunWith(AndroidJUnit4::class)
class MessagePhotoSourceStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val originals = mutableListOf<File>()
    private val stores = mutableListOf<MessagePhotoSourceStore>()

    @After fun cleanup() {
        stores.forEach { it.close() }
        originals.forEach { it.delete() }
    }

    private fun store(maximumBytes: Long = MessagePhotoSourceStore.MAXIMUM_SOURCE_BYTES,
                      input: ((Uri) -> InputStream?)? = null): MessagePhotoSourceStore =
        MessagePhotoSourceStore(context, maximumBytes, input).also(stores::add)

    private fun original(): File = File(context.cacheDir, "qa_source_original_${UUID.randomUUID()}.png")
        .also(originals::add)

    private fun png(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun bytes(uri: Uri): ByteArray = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
    private fun snapshots(): Set<String> = context.cacheDir.listFiles().orEmpty()
        .filter { it.name.startsWith("message_source_") }.map { it.name }.toSet()

    @Test fun changedAndDeletedOriginalNeverChangesPublishedSnapshot() = runBlocking {
        val file = original()
        png(file, Color.RED)
        val expected = file.readBytes()
        val store = store()
        val snapshot = store.sourceFor(file.toUri())
        assertNotEquals(file.toUri(), snapshot)
        assertEquals("image/png", store.mimeTypeFor(file.toUri()))
        assertEquals("image/png", store.mimeTypeFor(snapshot))
        assertArrayEquals(expected, bytes(snapshot))
        png(file, Color.BLUE)
        assertEquals(snapshot, store.sourceFor(file.toUri()))
        assertArrayEquals(expected, bytes(snapshot))
        assertTrue(file.delete())
        assertEquals(snapshot, store.sourceFor(file.toUri()))
        assertArrayEquals(expected, bytes(snapshot))
    }

    @Test fun concurrentRequestsForSameUriPublishOnlyOneCopy() = runBlocking {
        val file = original()
        png(file, Color.GREEN)
        val expected = file.readBytes()
        val opens = AtomicInteger()
        val store = store(input = { opens.incrementAndGet(); ByteArrayInputStream(expected) })
        val results = (1..8).map { async(Dispatchers.Default) { store.sourceFor(file.toUri()) } }.awaitAll()
        assertEquals(1, results.toSet().size)
        assertEquals(1, opens.get())
        assertArrayEquals(expected, bytes(results.first()))
    }

    @Test fun fragmentedGifHeaderOverridesMisleadingOriginalExtensionWithoutChangingBytes() = runBlocking {
        val file = original() // Its .png extension must not determine the snapshot's MIME.
        val expected = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(70) { 23 }
        file.writeBytes(expected)
        val store = store(input = { object : ByteArrayInputStream(expected) {
            private var fragments = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 1 + fragments++ % 3))
        } })
        val snapshot = store.sourceFor(file.toUri())
        assertEquals("image/gif", store.mimeTypeFor(file.toUri()))
        assertEquals("image/gif", store.mimeTypeFor(snapshot))
        assertTrue(snapshot.lastPathSegment.orEmpty().endsWith(".gif"))
        assertArrayEquals(expected, bytes(snapshot))
        assertArrayEquals(expected, file.readBytes())
    }

    @Test fun forgetRemovesOnlyItsSnapshotAndAllowsExplicitNewSelection() = runBlocking {
        val first = original().also { png(it, Color.RED) }
        val second = original().also { png(it, Color.GREEN) }
        val store = store()
        val firstCopy = store.sourceFor(first.toUri())
        val secondCopy = store.sourceFor(second.toUri())
        store.forget(first.toUri())
        assertFalse(File(requireNotNull(firstCopy.path)).exists())
        assertTrue(File(requireNotNull(secondCopy.path)).exists())
        assertTrue(first.exists())
        assertTrue(second.exists())
        png(first, Color.BLUE)
        val selectedAgain = store.sourceFor(first.toUri())
        assertNotEquals(firstCopy, selectedAgain)
        assertArrayEquals(first.readBytes(), bytes(selectedAgain))
        store.forget(secondCopy) // A verified owned snapshot URI is also safe to forget.
        assertFalse(File(requireNotNull(secondCopy.path)).exists())
        assertTrue(File(requireNotNull(selectedAgain.path)).exists())
        assertTrue(second.exists())
        store.close()
        store.close()
        assertFalse(File(requireNotNull(secondCopy.path)).exists())
        assertFalse(File(requireNotNull(selectedAgain.path)).exists())
        assertTrue(first.exists())
        assertTrue(second.exists())
    }

    @Test fun missingSnapshotDoesNotSilentlyRecopyNewOriginal() = runBlocking {
        val file = original().also { png(it, Color.RED) }
        val store = store()
        val snapshot = store.sourceFor(file.toUri())
        assertTrue(File(requireNotNull(snapshot.path)).delete())
        png(file, Color.BLUE)
        val result = runCatching { store.sourceFor(file.toUri()) }
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(file.exists())
    }

    @Test fun sizeLimitAndFailedStreamLeaveNoSnapshotOrOriginalDeletion() = runBlocking {
        val file = original().also { png(it, Color.RED) }
        val before = snapshots()
        val tooLarge = store(maximumBytes = 128, input = { ByteArrayInputStream(ByteArray(129) { 23 }) })
        assertTrue(runCatching { tooLarge.sourceFor(file.toUri()) }.exceptionOrNull() is IOException)
        val failed = store(input = { object : InputStream() {
            private var started = false
            override fun read(): Int = throw IOException("OWN_TEST_STREAM_FAILURE")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (started) throw IOException("OWN_TEST_STREAM_FAILURE")
                started = true
                val count = minOf(length, 32)
                for (index in offset until offset + count) buffer[index] = 23
                return count
            }
        } })
        assertTrue(runCatching { failed.sourceFor(file.toUri()) }.exceptionOrNull() is IOException)
        assertEquals(before, snapshots())
        assertTrue(file.exists())
    }

    private class PausedStream : InputStream() {
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private var calls = 0
        override fun read(): Int = -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            calls++
            if (calls > 2) return -1
            if (calls == 2) {
                paused.countDown()
                if (!release.await(10, TimeUnit.SECONDS)) throw IOException("OWN_TEST_GATE_TIMEOUT")
            }
            val count = minOf(length, 32)
            for (index in offset until offset + count) buffer[index] = 23
            return count
        }
        override fun close() { closed.set(true) }
    }

    private fun invalidateDuringCopy(close: Boolean) = runBlocking {
        val file = original().also { png(it, Color.RED) }
        val before = snapshots()
        val stream = PausedStream()
        val store = store(input = { stream })
        val copying = async(Dispatchers.Default) { runCatching { store.sourceFor(file.toUri()) } }
        try {
            assertTrue("Copy did not reach the owned-file stage", stream.paused.await(5, TimeUnit.SECONDS))
            assertEquals(1, (snapshots() - before).size)
            if (close) store.close() else store.forget(file.toUri())
        } finally { stream.release.countDown() }
        assertTrue(copying.await().exceptionOrNull() is IOException)
        assertEquals(before, snapshots())
        assertTrue(stream.closed.get())
        assertTrue(file.exists())
        assertNull(store.mimeTypeFor(file.toUri()))
        if (close) assertTrue(runCatching { store.sourceFor(file.toUri()) }.exceptionOrNull() is IOException)
    }

    @Test fun closeDuringCopyCannotPublishOrLeaveAnOwnedFile() { invalidateDuringCopy(close = true) }
    @Test fun forgetDuringCopyCannotPublishOrLeaveAnOwnedFile() { invalidateDuringCopy(close = false) }

    @Test fun cancellationDuringCopyClosesStreamsAndRemovesPartialFile() = runBlocking {
        val file = original().also { png(it, Color.RED) }
        val before = snapshots()
        val stream = PausedStream()
        val store = store(input = { stream })
        val copying = async(Dispatchers.Default) { store.sourceFor(file.toUri()) }
        try {
            assertTrue(stream.paused.await(5, TimeUnit.SECONDS))
            copying.cancel()
        } finally { stream.release.countDown() }
        copying.join()
        assertTrue(copying.isCancelled)
        assertTrue(stream.closed.get())
        assertEquals(before, snapshots())
        assertTrue(file.exists())
        assertNull(store.mimeTypeFor(file.toUri()))
    }
}
