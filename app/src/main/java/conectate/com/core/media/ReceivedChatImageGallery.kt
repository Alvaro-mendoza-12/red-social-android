package conectate.com.core.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import conectate.com.data.localfallback.UbuntuStorageUrl
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun shouldSaveReceivedChatImage(
    accountId: String?, senderId: String?, contentType: String,
    deletedForAll: Boolean = false, deletedBy: List<String> = emptyList()
): Boolean {
    val account = accountId?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return false
    val sender = senderId?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return false
    return sender != account && contentType.trim().lowercase() == "image" && !deletedForAll &&
        deletedBy.none { it.trim().lowercase() == account }
}

internal fun canonicalChatMediaSourceKey(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    return UbuntuStorageUrl.stableCacheKey(UbuntuStorageUrl.rewrite(raw) ?: raw)
        ?.trim()?.takeIf(String::isNotBlank)
}

/** Reuses a completed private download; never starts a second network transfer. */
internal class ReceivedChatImageGallery(
    private val context: Context,
    private val accountIdProvider: () -> String?,
    private val enabled: () -> Boolean
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pending = linkedMapOf<String, String>()

    fun register(source: String?, senderId: String?, type: String, deleted: Boolean, deletedBy: List<String>) {
        val account = accountIdProvider()?.trim()?.lowercase() ?: return
        val sourceKey = canonicalChatMediaSourceKey(source) ?: return
        if (!enabled() || !shouldSaveReceivedChatImage(account, senderId, type, deleted, deletedBy)) return
        synchronized(pending) {
            pending[sourceKey] = account
            while (pending.size > 256) pending.remove(pending.keys.first())
        }
    }

    fun onCachedMedia(account: String, sourceKey: String, file: File) {
        if (synchronized(pending) { pending[sourceKey] } != account) return
        scope.launch { runCatching { exportPending(account, sourceKey, file) } }
    }

    internal suspend fun exportPending(account: String, sourceKey: String, file: File): Uri? = mutex.withLock {
        if (synchronized(pending) { pending[sourceKey] } != account) return@withLock null
        fun checkActive() {
            check(enabled() && accountIdProvider()?.trim()?.lowercase() == account)
        }
        checkActive()
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val key = digest("$account:$sourceKey")
        val receipt = File(context.filesDir, "received-image-gallery/v1/${digest(account)}/$key.receipt")
        // Keep the receipt even if the user deletes the public copy: reopening
        // the conversation must not recreate an image they deliberately removed.
        if (receipt.isFile) {
            require(receipt.length() in 1..1024)
            synchronized(pending) { pending.remove(sourceKey) }
            return@withLock Uri.parse(receipt.readText())
        }
        val privateRoot = File(context.filesDir, "chat-media-offline").canonicalFile
        require(file.canonicalPath.startsWith(privateRoot.path + File.separator) && file.isFile)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val mime = bounds.outMimeType ?: error("Unknown image type")
        val extension = when (mime) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
            "image/gif" -> "gif"; "image/heic", "image/heif" -> "heic"
            else -> error("Unsupported gallery image")
        }
        val uri = savePublicMedia(context, "Novvin_received_${key.take(24)}.$extension", mime, ::checkActive) { output ->
            file.inputStream().use { copyMediaWithLimit(it, output, 64L * 1024 * 1024, ::checkActive) }
        }
        // Read one bounded receipt, rather than loading an ever-growing settings
        // map. Commit atomically after the public copy is complete.
        val partial = File(receipt.parentFile, "$key.part")
        try {
            check(receipt.parentFile!!.isDirectory || receipt.parentFile!!.mkdirs())
            partial.outputStream().use { output ->
                output.write(uri.toString().toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            check(partial.renameTo(receipt))
        } catch (error: Throwable) {
            partial.delete()
            if (uri.scheme == "file") uri.path?.let { File(it).delete() }
            else context.contentResolver.delete(uri, null, null)
            throw error
        }
        synchronized(pending) { pending.remove(sourceKey) }
        uri
    }
}
