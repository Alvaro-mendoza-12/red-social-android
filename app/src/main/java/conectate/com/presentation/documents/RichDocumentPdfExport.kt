package conectate.com.presentation.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.UUID

/** Preserve the supported DOCX text styles and embedded raster images in a private PDF copy. */
internal suspend fun createRichDocumentPdf(context: Context, originalName: String, html: String): File =
    withContext(Dispatchers.IO) {
        require(html.length <= 12_500_000) { "El documento es demasiado grande para convertirlo visualmente." }
        val body = html.substringAfter("<body>", "").substringBefore("</body>", "")
        require(body.isNotBlank()) { "El documento no tiene contenido visual para convertir." }
        val directory = File(context.cacheDir, "document-copies").apply { mkdirs() }
        val basename = safeDocumentName(originalName).substringBeforeLast('.').take(80)
        val output = File(directory, "$basename-visual-${UUID.randomUUID().toString().take(8)}.pdf")
        val images = mutableListOf<Bitmap>()
        var decodedPixels = 0L
        try {
            val imageGetter = Html.ImageGetter { source ->
                require(images.size < 20) { "El documento tiene demasiadas imágenes para convertirlo. Prueba PDF de texto." }
                val encoded = source.substringAfter("base64,", "")
                require(source.startsWith("data:image/") && encoded.isNotEmpty() && encoded.length <= 6_000_000) {
                    "La imagen del documento no se puede convertir con seguridad."
                }
                val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                    ?: error("La imagen del documento está dañada.")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..16_000 && bounds.outHeight in 1..16_000) {
                    "La imagen del documento es demasiado grande o no se puede leer."
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 1_200 || bounds.outHeight / inSampleSize > 1_200) {
                        inSampleSize *= 2
                    }
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: error("No se pudo dibujar una imagen del documento.")
                val pixels = bitmap.width.toLong() * bitmap.height
                if (decodedPixels + pixels > 8_000_000) {
                    bitmap.recycle()
                    error("Las imágenes del documento superan la memoria de conversión. Prueba PDF de texto.")
                }
                decodedPixels += pixels
                images += bitmap
                val scale = minOf(515f / bitmap.width, 700f / bitmap.height, 1f)
                BitmapDrawable(context.resources, bitmap).apply {
                    setBounds(0, 0, (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1))
                }
            }
            val spanned = Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT, imageGetter, null)
            require(spanned.isNotBlank()) { "El documento no contiene texto ni imágenes para exportar." }
            val paint = TextPaint().apply { color = Color.BLACK; textSize = 11f; isAntiAlias = true }
            val layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, 515)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(3f, 1f)
                .build()
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
                    pdf.finishPage(page)
                    firstLine = nextLine
                }
                output.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(renderer.pageCount > 0) { "El PDF generado no contiene páginas." }
                }
            }
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            images.forEach { it.recycle() }
        }
    }
