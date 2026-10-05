package conectate.com.presentation.messages

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** A composer's private byte snapshots. Call close when the composer is disposed. */
internal class MessagePhotoSourceStore(
    context: Context,
    private val maximumBytes: Long = MAXIMUM_SOURCE_BYTES,
    // Optional stream seam keeps race tests local without installing a test provider.
    private val openSource: ((Uri) -> InputStream?)? = null,
) {
    private val context = context.applicationContext
    private val cache = context.cacheDir.canonicalFile
    private val mutex = Mutex()
    // forget/close are synchronous: the guard also invalidates an in-flight copy
    // without waiting for the copying coroutine to release the mutex.
    private val guard = Any()
    private val snapshots = mutableMapOf<Uri, Snapshot>()
    private val epochs = mutableMapOf<Uri, Long>()
    private val ownedFiles = mutableMapOf<File, Uri>()
    private var closed = false

    private data class Snapshot(val file: File, val uri: Uri, val mimeType: String?)
    private data class SourceFormat(val extension: String, val mimeType: String?)

    init { require(maximumBytes in 1..MAXIMUM_SOURCE_BYTES) }

    suspend fun sourceFor(originalUri: Uri): Uri = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val epoch = synchronized(guard) {
            if (closed) throw IOException("PHOTO_SOURCE_STORE_CLOSED")
            snapshots[originalUri]?.let {
                // Never silently read a newer original after eviction of the snapshot.
                if (!it.file.isFile || it.file.length() == 0L) throw IOException("PHOTO_SOURCE_SNAPSHOT_LOST")
                return@withLock it.uri
            }
            epochs[originalUri] ?: 0L
        }
        var createdFile: File? = null
        try {
            val copied = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                verifyCurrent(originalUri, epoch)
                val input = if (openSource != null) openSource.invoke(originalUri)
                    else context.contentResolver.openInputStream(originalUri)
                if (input == null) throw IOException("PHOTO_SOURCE_UNAVAILABLE")
                input.use { source ->
                    val buffer = ByteArray(64 * 1024)
                    // Providers may return only one byte per read. Collect enough
                    // prefix bytes to identify GIF/PNG even on a fragmented stream.
                    var firstCount = 0
                    while (firstCount < 32) {
                        currentCoroutineContext().ensureActive()
                        verifyCurrent(originalUri, epoch)
                        val count = source.read(buffer, firstCount, 32 - firstCount)
                        if (count < 0) break
                        if (count == 0) {
                            val byte = source.read()
                            if (byte < 0) break
                            buffer[firstCount++] = byte.toByte()
                        } else firstCount += count
                        if (firstCount.toLong() > maximumBytes) throw IOException("PHOTO_SOURCE_TOO_LARGE")
                    }
                    if (firstCount == 0) throw IOException("PHOTO_SOURCE_EMPTY")
                    val format = formatFor(buffer, firstCount)
                    val (file, output) = synchronized(guard) {
                        verifyCurrentLocked(originalUri, epoch)
                        val candidate = File(cache, "message_source_${UUID.randomUUID()}.${format.extension}")
                        if (!candidate.createNewFile()) throw IOException("PHOTO_SOURCE_NAME_COLLISION")
                        createdFile = candidate
                        ownedFiles[candidate] = originalUri
                        candidate to candidate.outputStream()
                    }
                    output.use { destination ->
                        currentCoroutineContext().ensureActive()
                        verifyCurrent(originalUri, epoch)
                        destination.write(buffer, 0, firstCount)
                        var copiedBytes = firstCount.toLong()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            verifyCurrent(originalUri, epoch)
                            val count = readChunk(source, buffer)
                            if (count < 0) break
                            currentCoroutineContext().ensureActive()
                            verifyCurrent(originalUri, epoch)
                            if (copiedBytes + count > maximumBytes) throw IOException("PHOTO_SOURCE_TOO_LARGE")
                            destination.write(buffer, 0, count)
                            copiedBytes += count
                        }
                    }
                    if (!file.isFile || file.length() == 0L) throw IOException("PHOTO_SOURCE_COPY_INCOMPLETE")
                    currentCoroutineContext().ensureActive()
                    verifyCurrent(originalUri, epoch)
                    Snapshot(file, file.toUri(), format.mimeType)
                }
            }
            // Publish only after IO has returned successfully: cancellation during
            // dispatcher resumption is caught below and deletes the unpublished file.
            currentCoroutineContext().ensureActive()
            synchronized(guard) {
                verifyCurrentLocked(originalUri, epoch)
                snapshots[originalUri] = copied
            }
            copied.uri
        } catch (failure: Throwable) {
            createdFile?.let { file ->
                synchronized(guard) {
                    if (snapshots[originalUri]?.file == file) snapshots.remove(originalUri)
                    ownedFiles.remove(file)
                }
                deleteOwned(file)
            }
            throw failure
        }
    }

    /** Metadata comes from copied bytes, never a filename supplied by a user. */
    fun mimeTypeFor(uri: Uri): String? = synchronized(guard) {
        snapshots[uri]?.mimeType ?: snapshots.values.firstOrNull { it.uri == uri }?.mimeType
    }

    fun forget(originalUri: Uri) {
        val files = synchronized(guard) {
            if (closed) return
            val key = snapshots.entries.firstOrNull { it.value.uri == originalUri }?.key ?: originalUri
            epochs[key] = (epochs[key] ?: 0L) + 1L
            snapshots.remove(key)
            ownedFiles.filterValues { it == key }.keys.toList().also { owned ->
                owned.forEach { ownedFiles.remove(it) }
            }
        }
        files.forEach(::deleteOwned)
    }

    fun close() {
        val files = synchronized(guard) {
            if (closed) return
            closed = true
            snapshots.clear()
            epochs.clear()
            ownedFiles.keys.toList().also { ownedFiles.clear() }
        }
        files.forEach(::deleteOwned)
    }

    private fun verifyCurrent(originalUri: Uri, epoch: Long) = synchronized(guard) {
        verifyCurrentLocked(originalUri, epoch)
    }

    private fun verifyCurrentLocked(originalUri: Uri, epoch: Long) {
        if (closed) throw IOException("PHOTO_SOURCE_STORE_CLOSED")
        if ((epochs[originalUri] ?: 0L) != epoch) throw IOException("PHOTO_SOURCE_FORGOTTEN")
    }

    private fun deleteOwned(file: File) {
        // Callers supply only File objects created by this instance, never a URI's path.
        if (file.parentFile == cache && file.name.startsWith("message_source_")) file.delete()
    }

    private fun readChunk(source: InputStream, buffer: ByteArray): Int {
        val count = source.read(buffer)
        if (count != 0) return count
        // InputStream implementations may return zero without EOF.
        val byte = source.read()
        if (byte < 0) return -1
        buffer[0] = byte.toByte()
        return 1
    }

    private fun formatFor(bytes: ByteArray, count: Int): SourceFormat {
        fun matches(offset: Int, signature: IntArray): Boolean = count >= offset + signature.size &&
            signature.indices.all { (bytes[offset + it].toInt() and 255) == signature[it] }
        fun ascii(offset: Int, text: String): Boolean = matches(offset, text.map(Char::code).toIntArray())
        return when {
            matches(0, intArrayOf(255, 216, 255)) -> SourceFormat("jpg", "image/jpeg")
            matches(0, intArrayOf(137, 80, 78, 71, 13, 10, 26, 10)) -> SourceFormat("png", "image/png")
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> SourceFormat("gif", "image/gif")
            ascii(0, "RIFF") && ascii(8, "WEBP") -> SourceFormat("webp", "image/webp")
            ascii(4, "ftyp") && (ascii(8, "avif") || ascii(8, "avis")) -> SourceFormat("avif", "image/avif")
            ascii(4, "ftyp") && listOf("heic", "heix", "hevc", "hevx").any { ascii(8, it) } -> SourceFormat("heic", "image/heic")
            ascii(4, "ftyp") && (ascii(8, "mif1") || ascii(8, "msf1")) -> SourceFormat("heif", "image/heif")
            else -> SourceFormat("bin", null)
        }
    }

    companion object { const val MAXIMUM_SOURCE_BYTES: Long = 50L * 1024 * 1024 }
}
