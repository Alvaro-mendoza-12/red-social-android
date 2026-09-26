package conectate.com.presentation.documents

import android.content.Context
import android.content.ContentValues
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.NetworkSecurityPolicy
import conectate.com.data.localfallback.UbuntuStorageUrl
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

internal fun documentMimeType(name: String, fallback: String = "application/octet-stream"): String = when (
    name.substringAfterLast('.', "").lowercase()
) {
    "pdf" -> "application/pdf"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "txt", "md", "log" -> "text/plain"
    "csv" -> "text/csv"
    "json" -> "application/json"
    else -> fallback
}

/** Save to the user's visible Downloads/Novvin folder without showing a system destination picker. */
internal suspend fun saveDocumentToDownloads(context: Context, file: File, name: String, mime: String): Uri =
    saveToDownloads(context, name, mime) { destination ->
        require(file.isFile) { "La copia del documento ya no está disponible." }
        val resolver = context.contentResolver
        resolver.openOutputStream(destination, "w")?.use { sink ->
            file.inputStream().use { source ->
                val buffer = ByteArray(16_384)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                }
            }
        } ?: error("No se pudo escribir el archivo en Descargas.")
    }

private val documentDownloadClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).followSslRedirects(false).build()

/** The preview's 32 MB parsing ceiling must not prevent downloading a larger attachment. */
internal suspend fun copyDocumentSource(context: Context, source: Uri, destination: Uri) = withContext(Dispatchers.IO) {
    val sink = context.contentResolver.openOutputStream(destination, "w") ?: error("No se pudo escribir el documento.")
    sink.use { output ->
        when (source.scheme?.lowercase()) {
            "http", "https" -> {
                if (source.scheme.equals("http", true) &&
                    !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(source.host.orEmpty())) {
                    error("El enlace del documento usa HTTP sin cifrar y Android lo bloquea. Se necesita una URL HTTPS.")
                }
                val request = Request.Builder().url(source.toString()).apply {
                    UbuntuStorageUrl.publicRequestHeaders.forEach { (key, value) -> header(key, value) }
                }.build()
                documentDownloadClient.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "No se pudo descargar el documento (${response.code})." }
                    val body = response.body ?: error("El documento está vacío.")
                    body.byteStream().use { input -> copyDocumentStream(input, output) }
                }
            }
            "content", "file" -> context.contentResolver.openInputStream(source)?.use { input ->
                copyDocumentStream(input, output)
            } ?: error("Ya no se puede leer el documento. Renueva el permiso e inténtalo otra vez.")
            else -> error("La ubicación del documento no permite descarga.")
        }
    }
}

internal suspend fun saveDocumentSourceToDownloads(context: Context, source: Uri, name: String, mime: String): Uri =
    saveToDownloads(context, name, mime) { destination -> copyDocumentSource(context, source, destination) }

private suspend fun saveToDownloads(
    context: Context,
    name: String,
    mime: String,
    writer: suspend (Uri) -> Unit,
): Uri = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        error("El guardado directo requiere Android 10 o posterior.")
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, safeDocumentName(name))
        put(MediaStore.MediaColumns.MIME_TYPE, documentMimeType(name, mime))
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Novvin")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("No se pudo crear el archivo en Descargas.")
    try {
        writer(destination)
        resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        destination
    } catch (error: Throwable) {
        resolver.delete(destination, null, null)
        throw error
    }
}

private suspend fun copyDocumentStream(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(16_384)
    var copied = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        copied += count
        require(copied <= 100L * 1024 * 1024) { "El documento supera el límite de descarga de 100 MB." }
        output.write(buffer, 0, count)
    }
}

internal suspend fun createDocumentCopy(context: Context, originalName: String, text: String, asPdf: Boolean): File = withContext(Dispatchers.IO) {
    require(text.length <= DocumentLimits.TEXT_CHARACTERS) { "El texto supera el límite de exportación." }
    val directory = File(context.cacheDir, "document-copies").apply { mkdirs() }
    // Generated copies outlive the preview so an asynchronous attachment upload can still read them.
    directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1_000 }?.forEach(File::delete)
    val basename = safeDocumentName(originalName).substringBeforeLast('.').take(80)
    val extension = if (asPdf) "pdf" else originalName.substringAfterLast('.', "txt").lowercase()
        .takeIf { it in DocumentReadSession.textExtensions } ?: "txt"
    val output = File(directory, "$basename-copia-${UUID.randomUUID().toString().take(8)}.$extension")
    try {
        if (asPdf) writeTextPdf(output, text) else output.writeText(text, Charsets.UTF_8)
        currentCoroutineContext().ensureActive()
        output
    } catch (error: Throwable) {
        output.delete()
        throw error
    }
}

private suspend fun writeTextPdf(output: File, text: String) {
    val paint = TextPaint().apply { color = Color.BLACK; textSize = 11f; isAntiAlias = true }
    val layout = StaticLayout.Builder.obtain(text.ifBlank { " " }, 0, text.ifBlank { " " }.length, paint, 515)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f, 1f).build()
    val pdf = PdfDocument()
    try {
        var firstLine = 0
        var pageNumber = 0
        while (firstLine < layout.lineCount) {
            currentCoroutineContext().ensureActive()
            require(++pageNumber <= 300) { "La conversión supera las 300 páginas." }
            val firstTop = layout.getLineTop(firstLine)
            var nextLine = firstLine + 1
            while (nextLine < layout.lineCount && layout.getLineBottom(nextLine) - firstTop <= 750) nextLine++
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            page.canvas.save()
            page.canvas.translate(40f, 40f - firstTop)
            page.canvas.clipRect(0f, firstTop.toFloat(), 515f, layout.getLineTop(nextLine).toFloat())
            layout.draw(page.canvas)
            page.canvas.restore()
            page.canvas.drawText("$pageNumber", 290f, 818f, paint)
            pdf.finishPage(page)
            firstLine = nextLine
        }
        output.outputStream().use(pdf::writeTo)
    } finally { pdf.close() }
}

internal suspend fun saveDocumentCopy(context: Context, copy: File, destination: Uri, original: Uri) = withContext(Dispatchers.IO) {
    require(destination != original) { "Elige un destino nuevo: el original se conserva." }
    val output = context.contentResolver.openOutputStream(destination, "w") ?: error("No se pudo crear la copia.")
    output.use { sink ->
        copy.inputStream().use { source ->
            val buffer = ByteArray(16_384)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                sink.write(buffer, 0, count)
            }
        }
    }
}
