package conectate.com.presentation.documents

import conectate.com.core.media.readMediaBytesWithLimit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.security.NetworkSecurityPolicy
import conectate.com.data.localfallback.UbuntuStorageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed interface DocumentBody {
    data class Text(val value: String) : DocumentBody
    data class Office(val value: ParsedOfficeDocument) : DocumentBody
    data class Pdf(val pages: Int) : DocumentBody
    data class Unsupported(val reason: String) : DocumentBody
}

internal data class OpenedDocument(val name: String, val mimeType: String, val file: File?, val size: Long, val body: DocumentBody)

/** Each dialog owns one immutable local snapshot, including sources supplied by a non-seekable provider. */
internal class DocumentReadSession(private val context: Context) : AutoCloseable {
    private val directory = File(context.cacheDir, "document-preview/${UUID.randomUUID()}")
    @Volatile private var closed = false
    private var guardedRemoteRead = false
    private var remoteIdentity: conectate.com.core.media.StorageReadIdentity? = null

    private fun snapshotFile(): File = synchronized(this) {
        check(!closed)
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "source.snapshot")
    }

    suspend fun open(uri: Uri, suggestedName: String? = null): OpenedDocument = withContext(Dispatchers.IO) {
        guardedRemoteRead = conectate.com.core.media.StorageReadSession.isStorageRequest(uri.toString())
        remoteIdentity = if (guardedRemoteRead) conectate.com.core.media.StorageReadSession.snapshot()?.identity else null
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Documento"
        var mime = "application/octet-stream"
        var declaredSize = -1L
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declaredSize = cursor.getLong(it) }
                }
            }
            mime = context.contentResolver.getType(uri) ?: mime
        } else if (uri.scheme == "file") {
            declaredSize = uri.path?.let { File(it).length() } ?: -1L
        }
        if (!suggestedName.isNullOrBlank() && suggestedName.substringAfterLast('.', "").lowercase() in knownExtensions) name = suggestedName.removePrefix("📎").trim()
        name = safeDocumentName(name)
        if (declaredSize > DocumentLimits.INPUT_BYTES) {
            return@withContext OpenedDocument(name, mime, null, declaredSize,
                DocumentBody.Unsupported("La vista interna admite archivos de hasta 32 MB. Puedes enviar el original."))
        }
        val file = snapshotFile()
        try {
            when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    if (uri.scheme.equals("http", true) &&
                        !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(uri.host.orEmpty())) {
                        throw IOException("El enlace del documento usa HTTP sin cifrar y Android lo bloquea. Se necesita una URL HTTPS.")
                    }
                    val request = Request.Builder().url(uri.toString())
                    UbuntuStorageUrl.publicRequestHeaders.forEach { (key, value) -> request.header(key, value) }
                    remoteClient.newCall(request.build()).awaitDocumentResponse().use { response ->
                        check(response.isSuccessful) { "No se pudo descargar el documento (${response.code})." }
                        val body = response.body ?: throw IOException("El documento está vacío.")
                        require(body.contentLength() <= DocumentLimits.INPUT_BYTES) { "El documento supera 32 MB." }
                        mime = body.contentType()?.toString()?.substringBefore(';') ?: mime
                        body.byteStream().use { copyBounded(it, file) }
                    }
                }
                "content", "file" -> context.contentResolver.openInputStream(uri)?.use { copyBounded(it, file) }
                    ?: throw IOException("No se pudo abrir el documento. Selecciónalo de nuevo para renovar el permiso.")
                else -> throw IOException("La ubicación del documento no es compatible.")
            }
            currentCoroutineContext().ensureActive()
            val prefix = file.inputStream().use { it.readBytesAtMost(8) }
            val extension = name.substringAfterLast('.', "").lowercase()
            val body = when {
                prefix.toString(Charsets.US_ASCII).startsWith("%PDF-") -> {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            require(renderer.pageCount > 0) { "El PDF no contiene páginas." }
                            DocumentBody.Pdf(renderer.pageCount)
                        }
                    }
                }
                prefix.size >= 2 && prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte() -> {
                    val coroutine = currentCoroutineContext()
                    DocumentBody.Office(file.inputStream().use { input -> DocumentContentParser.parseOffice(input) { coroutine.ensureActive() } })
                }
                extension in textExtensions || mime.startsWith("text/") || mime in setOf("application/json", "application/xml") -> {
                    require(file.length() <= DocumentLimits.TEXT_BYTES) { "El texto supera 512 KB para la edición interna." }
                    DocumentBody.Text(DocumentContentParser.decodeText(file.inputStream().use { it.readMediaBytesWithLimit(DocumentLimits.TEXT_BYTES.toLong()) }))
                }
                extension in setOf("doc", "xls", "ppt") -> DocumentBody.Office(ParsedOfficeDocument(
                    when (extension) { "doc" -> "Word"; "xls" -> "Excel"; else -> "PowerPoint" } + " · lectura",
                    emptyList(),
                ))
                else -> DocumentBody.Unsupported("Este formato se conserva como archivo. La edición interna admite texto; Office moderno se abre en lectura. Los formatos DOC, XLS y PPT antiguos requieren conversión previa.")
            }
            if (guardedRemoteRead) conectate.com.core.media.StorageReadSession.requireCurrent(remoteIdentity)
            OpenedDocument(name, mime, file, file.length(), body)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    suspend fun renderOffice(opened: OpenedDocument, tokenOverride: String? = null): OfficePdfPreview = withContext(Dispatchers.IO) {
        if (guardedRemoteRead) conectate.com.core.media.StorageReadSession.requireCurrent(remoteIdentity)
        val original = checkNotNull(opened.file)
        val preview = synchronized(this@DocumentReadSession) {
            check(!closed)
            File(directory, "office-preview.pdf")
        }
        try {
            DocumentOfficePreviewClient.convert(context, original, opened.name, tokenOverride).use { response ->
                val body = response.body ?: throw IOException("La vista del documento está vacía.")
                require(body.contentLength() <= DocumentLimits.INPUT_BYTES) { "La vista supera 32 MB." }
                body.byteStream().use { copyBounded(it, preview) }
            }
            currentCoroutineContext().ensureActive()
            require(preview.inputStream().use { it.readBytesAtMost(5) }.toString(Charsets.US_ASCII) == "%PDF-") {
                "El servidor no devolvió una vista PDF válida."
            }
            val count = ParcelFileDescriptor.open(preview, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
            }
            require(count > 0) { "La vista del documento no contiene páginas." }
            OfficePdfPreview(preview, count)
        } catch (error: Throwable) { preview.delete(); throw error }
    }

    private suspend fun copyBounded(input: InputStream, file: File) = suspendCancellableCoroutine<Unit> { continuation ->
        // Closing a provider/network stream interrupts an outstanding read when the dialog closes.
        continuation.invokeOnCancellation { runCatching { input.close() } }
        try {
            file.outputStream().use { output ->
                val buffer = ByteArray(16_384)
                var count = 0L
                while (continuation.isActive) {
                    check(!closed) { "La vista del documento ya se cerró." }
                    if (guardedRemoteRead) conectate.com.core.media.StorageReadSession.requireCurrent(remoteIdentity)
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= DocumentLimits.INPUT_BYTES) { "El documento supera 32 MB." }
                    output.write(buffer, 0, read)
                }
            }
            if (continuation.isActive) continuation.resume(Unit)
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    override fun close() = synchronized(this) {
        closed = true
        directory.deleteRecursively()
        Unit
    }

    companion object {
        val textExtensions = setOf("txt", "csv", "tsv", "json", "md", "xml", "log", "yaml", "yml")
        private val knownExtensions = textExtensions + setOf("pdf", "docx", "xlsx", "pptx", "doc", "xls", "ppt")
        // Reuse the media pool, TLS sessions and the existing account guards.
        // Opening a document no longer starts an independent network stack.
        private val remoteClient = conectate.com.util.MediaPlaybackDataSources.networkClient().newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).followSslRedirects(false).build()
    }
}

internal fun safeDocumentName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
    .replace(Regex("[\\p{Cntrl}<>:\"|?*]"), "_").trim().take(160).ifBlank { "Documento" }

private fun InputStream.readBytesAtMost(limit: Int): ByteArray {
    val bytes = ByteArray(limit)
    var count = 0
    while (count < limit) {
        val read = read(bytes, count, limit - count)
        if (read < 0) break
        count += read
    }
    return bytes.copyOf(count)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun Call.awaitDocumentResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { response.close() })
        }
    })
}

internal suspend fun renderDocumentPdfPage(file: File, pageIndex: Int): Bitmap = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(pageIndex.coerceIn(0, renderer.pageCount - 1)).use { page ->
                val scale = minOf(1_200f / page.width.coerceAtLeast(1), 1_600f / page.height.coerceAtLeast(1), 2f)
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    currentCoroutineContext().ensureActive()
                    bitmap
                } catch (error: Throwable) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
    }
}
