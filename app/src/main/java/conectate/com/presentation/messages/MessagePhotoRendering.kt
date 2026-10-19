package conectate.com.presentation.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.min

/** Points belong to the EXIF-oriented source photo, before the user's quarter-turns. */
internal data class MessagePhotoRenderState(
    val rotateQuarterTurns: Int = 0,
    val overlayText: String = "",
    val overlayEmoji: String = "",
    val strokes: List<List<Offset>> = emptyList(),
    val brushColor: Int = AndroidColor.WHITE,
    val strokeColors: List<Int> = emptyList(),
    val strokeWidths: List<Float> = emptyList(),
    val textSize: Float = 0.052f,

)

private fun quarterTurns(value: Int): Int = ((value % 4) + 4) % 4

/** Null rejects the letterbox/outside region rather than projecting it onto the photo. */
internal fun messagePhotoSourcePoint(displayedPoint: Offset, rotateQuarterTurns: Int): Offset? {
    val x = displayedPoint.x
    val y = displayedPoint.y
    if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
    return when (quarterTurns(rotateQuarterTurns)) {
        1 -> Offset(y, 1f - x)
        2 -> Offset(1f - x, 1f - y)
        3 -> Offset(1f - y, x)
        else -> displayedPoint
    }
}

internal fun messagePhotoDisplayPoint(sourcePoint: Offset, rotateQuarterTurns: Int): Offset =
    when (quarterTurns(rotateQuarterTurns)) {
        1 -> Offset(1f - sourcePoint.y, sourcePoint.x)
        2 -> Offset(1f - sourcePoint.x, 1f - sourcePoint.y)
        3 -> Offset(sourcePoint.y, 1f - sourcePoint.x)
        else -> sourcePoint
    }

/** Deletes incomplete output, including cancellation when returning from the dispatcher. */
internal suspend fun exportMessagePhoto(
    context: Context,
    sourceUri: Uri,
    state: MessagePhotoRenderState,
): Uri {
    var output: File? = null
    try {
        return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val source = decodeMessagePhoto(context, sourceUri, 2560)
            var rendered: Bitmap? = null
            try {
                currentCoroutineContext().ensureActive()
                val image = renderMessagePhoto(source, state)
                rendered = image
                currentCoroutineContext().ensureActive()
                val file = File(context.cacheDir, "message_media_${UUID.randomUUID()}.jpg")
                output = file
                file.outputStream().use { stream ->
                    if (!image.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                        throw IOException("IMAGE_ENCODE_FAILED")
                    }
                }
                if (!file.isFile || file.length() == 0L) throw IOException("IMAGE_OUTPUT_EMPTY")
                currentCoroutineContext().ensureActive()
                file.toUri()
            } finally {
                rendered?.recycle()
                source.recycle()
            }
        }
    } catch (failure: Throwable) {
        output?.delete()
        throw failure
    }
}

internal fun renderMessagePhoto(source: Bitmap, state: MessagePhotoRenderState): Bitmap {
    val swapped = quarterTurns(state.rotateQuarterTurns) % 2 != 0
    val result = Bitmap.createBitmap(
        if (swapped) source.height else source.width,
        if (swapped) source.width else source.height,
        Bitmap.Config.ARGB_8888,
    )
    try {
        drawMessagePhoto(AndroidCanvas(result), source, state)
        return result
    } catch (failure: Throwable) {
        result.recycle()
        throw failure
    }
}

/** Both display and JPEG use this renderer and the same proportional paint metrics. */
private fun drawMessagePhoto(canvas: AndroidCanvas, source: Bitmap, state: MessagePhotoRenderState) {
    val width = source.width.toFloat()
    val height = source.height.toFloat()
    val save = canvas.save()
    try {
        when (quarterTurns(state.rotateQuarterTurns)) {
            1 -> { canvas.translate(height, 0f); canvas.rotate(90f) }
            2 -> { canvas.translate(width, height); canvas.rotate(180f) }
            3 -> { canvas.translate(0f, width); canvas.rotate(270f) }
        }
        canvas.clipRect(0f, 0f, width, height)
        canvas.drawColor(AndroidColor.WHITE)
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        drawPhotoAnnotations(canvas, width, height, state)
    } finally {
        canvas.restoreToCount(save)
    }
}

private fun drawPhotoAnnotations(canvas: AndroidCanvas, width: Float, height: Float, state: MessagePhotoRenderState) {
    val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = state.brushColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = width * 0.008f
    }
    state.strokes.forEachIndexed { index, stroke ->
        brush.color = state.strokeColors.getOrNull(index) ?: state.brushColor
        brush.strokeWidth = width * (state.strokeWidths.getOrNull(index) ?: 0.008f).coerceIn(0.002f, 0.04f)
        stroke.zipWithNext().forEach { (start, end) ->
            if (start.x.isFinite() && start.y.isFinite() && end.x.isFinite() && end.y.isFinite()) {
                canvas.drawLine(start.x * width, start.y * height, end.x * width, end.y * height, brush)
            }
        }
    }
    val label = listOf(state.overlayEmoji, state.overlayText).filter(String::isNotBlank).joinToString(" ")
    if (label.isBlank()) return
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textSize = width * state.textSize.coerceIn(0.025f, 0.12f)
        textAlign = Paint.Align.CENTER
    }
    val lines = mutableListOf<String>()
    label.take(800).split('\n').forEach { paragraph ->
        var remainder = paragraph
        if (remainder.isEmpty()) lines += ""
        while (remainder.isNotEmpty()) {
            val count = text.breakText(remainder, true, width * 0.84f, null).coerceAtLeast(1)
            lines += remainder.take(count)
            remainder = remainder.drop(count)
        }
    }
    val metrics = text.fontMetrics
    val lineHeight = metrics.descent - metrics.ascent + width * 0.008f
    val textHeight = lines.size * lineHeight
    val padding = width * 0.025f
    val widest = lines.maxOfOrNull(text::measureText) ?: 0f
    val top = (height - textHeight) / 2f
    canvas.drawRoundRect(
        RectF((width - widest) / 2f - padding, top - padding, (width + widest) / 2f + padding, top + textHeight + padding),
        width * 0.02f, width * 0.02f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(98, 0, 0, 0) },
    )
    lines.forEachIndexed { index, line ->
        canvas.drawText(line, width / 2f, top - metrics.ascent + index * lineHeight, text)
    }
}

private fun decodeMessagePhoto(context: Context, uri: Uri, maximumDimension: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maximumDimension) {
                val scale = maximumDimension.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = context.contentResolver.openInputStream(uri) ?: throw IOException("IMAGE_SOURCE_UNAVAILABLE")
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("IMAGE_DECODE_FAILED")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maximumDimension) sample *= 2
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } ?: throw IOException("IMAGE_DECODE_FAILED")
    try {
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return bitmap
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
    }
}

private data class PhotoPreviewKey(val uri: Uri, val bitmap: Bitmap?, val state: MessagePhotoRenderState, val activeStroke: List<Offset>, val attempt: Int)
private class PhotoPreviewReadiness { var key: PhotoPreviewKey? = null; var ready = false }

@Composable
internal fun MessagePhotoPreview(
    sourceUri: Uri,
    state: MessagePhotoRenderState,
    drawEnabled: Boolean,
    interactionEnabled: Boolean,
    onStrokeAdded: (List<Offset>) -> Unit,
    onReadyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val strokeAdded by rememberUpdatedState(onStrokeAdded)
    val readyChanged by rememberUpdatedState(onReadyChanged)
    var attempt by remember(sourceUri) { mutableStateOf(0) }
    var bitmap by remember(sourceUri, attempt) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(sourceUri, attempt) { mutableStateOf(false) }
    var activeStroke by remember(sourceUri, state.rotateQuarterTurns, drawEnabled, interactionEnabled) { mutableStateOf<List<Offset>>(emptyList()) }
    var representedKey by remember { mutableStateOf<PhotoPreviewKey?>(null) }
    val readiness = remember { PhotoPreviewReadiness() }
    val key = PhotoPreviewKey(sourceUri, bitmap, state, activeStroke, attempt)
    SideEffect {
        if (readiness.key != key) {
            readiness.key = key
            readiness.ready = false
            representedKey = null
            readyChanged(false)
        }
    }
    LaunchedEffect(sourceUri, attempt) {
        var decoded: Bitmap? = null
        var published = false
        try {
            withContext(Dispatchers.Default) {
                decoded = decodeMessagePhoto(context, sourceUri, 1024)
                currentCoroutineContext().ensureActive()
            }
            bitmap = decoded
            published = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure = true
            readyChanged(false)
        } finally {
            if (!published) decoded?.recycle()
            // Published bitmaps can still be referenced by Android's render thread;
            // let GC release them after composition instead of recycling a bound texture.
        }
    }
    BoxWithConstraints(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val source = bitmap
        if (failure) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No se pudo mostrar la foto editada.", color = Color.White)
                TextButton(onClick = { attempt++ }, enabled = interactionEnabled) { Text("Reintentar") }
            }
        } else if (source == null) {
            CircularProgressIndicator()
        } else {
            val swapped = quarterTurns(state.rotateQuarterTurns) % 2 != 0
            val photoWidth = if (swapped) source.height else source.width
            val photoHeight = if (swapped) source.width else source.height
            val aspect = photoWidth.toFloat() / photoHeight
            val availableWidth = maxWidth.value.takeIf { it.isFinite() } ?: photoWidth.toFloat()
            val availableHeight = maxHeight.value.takeIf { it.isFinite() } ?: photoHeight.toFloat()
            val fittedWidth = min(availableWidth, availableHeight * aspect)
            val fittedHeight = fittedWidth / aspect
            Canvas(
                modifier = Modifier.size(fittedWidth.dp, fittedHeight.dp).clipToBounds()
                    .testTag("message_photo_preview")
                    .semantics {
                        contentDescription = "Foto editada"
                        stateDescription = if (representedKey == key) "Lista" else "Preparando"
                    }
                    .pointerInput(sourceUri, state.rotateQuarterTurns, drawEnabled, interactionEnabled) {
                        if (!drawEnabled || !interactionEnabled) return@pointerInput
                        fun sourcePoint(point: Offset): Offset? = if (size.width > 0 && size.height > 0) {
                            messagePhotoSourcePoint(Offset(point.x / size.width, point.y / size.height), state.rotateQuarterTurns)
                        } else null
                        detectDragGestures(
                            onDragStart = { point -> activeStroke = sourcePoint(point)?.let(::listOf) ?: emptyList() },
                            onDrag = { change, _ ->
                                val point = sourcePoint(change.position)
                                if (activeStroke.isNotEmpty()) {
                                    change.consume()
                                    if (point != null) activeStroke = activeStroke + point
                                }
                            },
                            onDragEnd = {
                                if (activeStroke.size > 1) strokeAdded(activeStroke.toList())
                                activeStroke = emptyList()
                            },
                            onDragCancel = { activeStroke = emptyList() },
                        )
                    },
            ) {
                var drawn = false
                try {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val save = native.save()
                        try {
                            native.scale(size.width / photoWidth, size.height / photoHeight)
                            drawMessagePhoto(native, source, if (activeStroke.isEmpty()) state else state.copy(strokes = state.strokes + listOf(activeStroke)))
                        } finally {
                            native.restoreToCount(save)
                        }
                    }
                    drawn = true
                } catch (_: Exception) {
                    failure = true
                    readyChanged(false)
                }
                if (drawn && activeStroke.isEmpty() && readiness.key == key && !readiness.ready) {
                    readiness.ready = true
                    representedKey = key
                    readyChanged(true)
                }
            }
        }
    }
}
