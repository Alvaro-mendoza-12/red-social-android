package conectate.com.util

import android.content.Context
import conectate.com.core.media.StorageReadDownloads
import conectate.com.core.media.StorageReadIdentity
import conectate.com.core.media.StorageReadSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** An internal delivery file belongs to one playback attempt and is deleted on release. */
internal class NativeAudioDelivery private constructor(
    val source: String, val identity: StorageReadIdentity?, private val file: File?,
) : AutoCloseable {
    fun requireCurrent() = StorageReadSession.requireCurrent(identity)
    override fun close() { file?.delete() }

    companion object {
        suspend fun prepare(context: Context, url: String): NativeAudioDelivery {
            val identity = StorageReadSession.snapshot()?.identity
            val resolved = UbuntuMedia.resolveUrl(url) ?: url
            if (!StorageReadSession.isStorageRequest(resolved)) {
                StorageReadSession.requireCurrent(identity)
                return NativeAudioDelivery(resolved, identity, null)
            }
            val destination = withContext(Dispatchers.IO) {
                File(context.cacheDir, "native-audio-deliveries").apply { mkdirs() }
                    .let { File(it, "${UUID.randomUUID()}.m4a") }
            }
            try {
                StorageReadDownloads.downloadToFile(resolved, destination, 100L * 1024 * 1024,
                    allowedMimeTypes = ::isAudioDeliveryContentType)
                currentCoroutineContext().ensureActive()
                StorageReadSession.requireCurrent(identity)
                return NativeAudioDelivery(destination.absolutePath, identity, destination)
            } catch (error: Throwable) { destination.delete(); throw error }
        }
    }
}
