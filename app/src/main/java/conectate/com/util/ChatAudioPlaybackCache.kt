package conectate.com.util

import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.core.media.StorageReadSession
import conectate.com.core.media.StorageReadIdentity
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Complete, bounded files for MediaPlayer; partial downloads are never playable. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ChatAudioPlaybackCache(
    private val directory: File,
    private val calls: Call.Factory = MediaPlaybackDataSources.networkClient(),
    private val maxFileBytes: Long = 100L * 1024 * 1024,
    private val maxCacheBytes: Long = 128L * 1024 * 1024,
) {
    fun download(url: String): File {
        val protectedRead = StorageReadSession.isStorageRequest(url)
        val identity = if (protectedRead) StorageReadSession.snapshot()?.identity else null
        val target = fileFor(url)
        if (!protectedRead && target.isFile && target.length() in 1..maxFileBytes) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val request = Request.Builder().url(url).apply {
            UbuntuStorageUrl.publicRequestHeaders.forEach { (key, value) -> header(key, value) }
        }.build()
        require(request.url.isHttps) { "El audio remoto debe usar HTTPS" }
        calls.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("No se pudo descargar el audio (${response.code})")
            val body = response.body ?: throw IOException("El audio está vacío")
            val contentType = body.contentType()?.toString()?.substringBefore(';').orEmpty()
            if (!isAudioDeliveryContentType(contentType)) {
                throw IOException("El servidor no devolvió un archivo de audio")
            }
            val expectedBytes = body.contentLength()
            if (expectedBytes > maxFileBytes) throw IOException("El audio supera el límite permitido")
            return body.byteStream().use { store(url, it, expectedBytes, identity) }
        }
    }

    fun store(url: String, input: InputStream, expectedBytes: Long = -1L, identity: StorageReadIdentity? = StorageReadSession.snapshot()?.identity): File {
        val target = fileFor(url, privateDelivery = StorageReadSession.isStorageRequest(url))
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo guardar el audio")
        val temporary = File(directory, "${target.name}.${UUID.randomUUID()}.part")
        try {
            var count = 0L
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (StorageReadSession.isStorageRequest(url)) StorageReadSession.requireCurrent(identity)
                    val read = input.read(buffer)
                    if (StorageReadSession.isStorageRequest(url)) StorageReadSession.requireCurrent(identity)
                    if (read < 0) break
                    count += read
                    if (count > maxFileBytes) throw IOException("El audio supera el límite permitido")
                    output.write(buffer, 0, read)
                }
            }
            if (count == 0L || expectedBytes >= 0 && count != expectedBytes) {
                throw IOException("La descarga del audio quedó incompleta")
            }
            if (StorageReadSession.isStorageRequest(url)) StorageReadSession.requireCurrent(identity)
            // A concurrent completed download is also valid; never expose a partial file.
            if (!temporary.renameTo(target) && !(target.isFile && target.length() == count)) {
                throw IOException("No se pudo finalizar la descarga del audio")
            }
            evictOldFiles(target)
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun fileFor(url: String, privateDelivery: Boolean = false): File {
        val key = StorageReadSession.scopedCacheKey(url, UbuntuStorageUrl.stableCacheKey(url) ?: url)
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val revision = if (privateDelivery) ".${UUID.randomUUID()}" else ""
        return File(directory, "$digest$revision.m4a")
    }

    private fun evictOldFiles(keep: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "m4a" }.orEmpty()
        var bytes = files.sumOf { it.length() }
        files.filter { it != keep }.sortedBy { it.lastModified() }.forEach { file ->
            if (bytes > maxCacheBytes) {
                val size = file.length()
                if (file.delete()) bytes -= size
            }
        }
    }
}
