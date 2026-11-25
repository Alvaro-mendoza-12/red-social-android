package conectate.com.presentation.messages.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.exifinterface.media.ExifInterface
import conectate.com.core.media.exifOrientationMatrix
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.max

@WorkerThread
suspend fun loadStickerBitmapFromUri(
    context: Context,
    uri: Uri,
    maxDimension: Int = 1400
): Bitmap? = withContext(Dispatchers.IO) {
    if (maxDimension <= 0) return@withContext null
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            decodeStickerBitmap(source, maxDimension)
        } else {
            decodeLegacyStickerBitmap(context, uri, maxDimension)
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

@RequiresApi(Build.VERSION_CODES.P)
internal fun decodeStickerBitmap(source: ImageDecoder.Source, maxDimension: Int): Bitmap {
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.isMutableRequired = true
        val width = info.size.width
        val height = info.size.height
        val edge = max(width, height)
        if (edge > maxDimension) {
            decoder.setTargetSize(
                scaledStickerSide(width, edge, maxDimension),
                scaledStickerSide(height, edge, maxDimension),
            )
        }
    }
}

internal fun decodeLegacyStickerBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = resolver.openInputStream(uri) ?: return null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val edge = max(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
        inSampleSize = stickerDecodeSampleSize(edge, maxDimension)
        inMutable = true
    }
    val orientation = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val original = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null
    val decodedEdge = max(original.width, original.height)
    val scaled = if (decodedEdge > maxDimension) {
        try {
            Bitmap.createScaledBitmap(
                original,
                scaledStickerSide(original.width, decodedEdge, maxDimension),
                scaledStickerSide(original.height, decodedEdge, maxDimension),
                true,
            )
        } finally {
            original.recycle()
        }
    } else {
        original
    }
    val matrix = exifOrientationMatrix(orientation)
    if (matrix.isIdentity) return scaled
    // Bitmap.createBitmap may return its input for an equivalent transform.
    var oriented: Bitmap? = null
    try {
        oriented = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
        return oriented
    } finally {
        if (oriented !== scaled) scaled.recycle()
    }
}

internal fun stickerDecodeSampleSize(edge: Int, maxDimension: Int): Int {
    require(edge > 0 && maxDimension > 0)
    var sample = 1
    // Keep enough pixels for the exact resize and cap the intermediate edge at twice the target.
    while ((edge.toLong() + sample - 1) / sample > maxDimension * 2L) sample *= 2
    return sample
}

private fun scaledStickerSide(side: Int, edge: Int, maxDimension: Int): Int {
    return (side.toLong() * maxDimension / edge).toInt().coerceAtLeast(1)
}

suspend fun removeStickerBackgroundAi(bitmap: Bitmap): Result<Bitmap> = withContext(Dispatchers.Default) {
    suspendCancellableCoroutine { continuation ->
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)
        segmenter.process(image)
            .addOnSuccessListener { mask ->
                val result = runCatching {
                    val maskWidth = mask.width
                    val maskHeight = mask.height
                    val scaledSource = if (bitmap.width != maskWidth || bitmap.height != maskHeight) {
                        Bitmap.createScaledBitmap(bitmap, maskWidth, maskHeight, true)
                    } else {
                        bitmap
                    }

                    val sourcePixels = IntArray(maskWidth * maskHeight)
                    scaledSource.getPixels(sourcePixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
                    val outPixels = IntArray(sourcePixels.size)
                    val floatBuffer = mask.buffer
                    floatBuffer.rewind()

                    for (i in sourcePixels.indices) {
                        val confidence = floatBuffer.float.coerceIn(0f, 1f)
                        val alpha = if (confidence <= 0.08f) 0 else (confidence * 255f).toInt().coerceIn(0, 255)
                        outPixels[i] = (alpha shl 24) or (sourcePixels[i] and 0x00FFFFFF)
                    }

                    val segmented = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                    segmented.setPixels(outPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
                    if (maskWidth != bitmap.width || maskHeight != bitmap.height) {
                        Bitmap.createScaledBitmap(segmented, bitmap.width, bitmap.height, true)
                    } else {
                        segmented
                    }
                }
                segmenter.close()
                if (continuation.isActive) continuation.resume(result)
            }
            .addOnFailureListener { error ->
                segmenter.close()
                if (continuation.isActive) continuation.resume(Result.failure(error))
            }
    }
}

fun exportStickerPng(
    source: Bitmap,
    transform: StickerEditorTransform,
    canvasSize: Int = 512,
    drawStrokes: List<List<Pair<Float, Float>>> = emptyList(),
    previewSizePx: Float = 320f
): ByteArray {
    val output = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    val matrix = Matrix()
    matrix.postTranslate(-source.width / 2f, -source.height / 2f)
    matrix.postScale(transform.scale, transform.scale)
    matrix.postRotate(transform.rotation)
    matrix.postTranslate(
        canvasSize / 2f + transform.offsetX,
        canvasSize / 2f + transform.offsetY
    )
    canvas.drawBitmap(source, matrix, paint)

    if (transform.overlayEmoji.isNotBlank()) {
        val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(transform.overlayEmoji.take(6), canvasSize / 2f, canvasSize - 88f, emojiPaint)
    }

    if (drawStrokes.isNotEmpty()) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
        }
        val scaleFactor = if (previewSizePx <= 0f) 1f else canvasSize.toFloat() / previewSizePx
        drawStrokes.forEach { stroke ->
            if (stroke.size > 1) {
                for (i in 1 until stroke.size) {
                    val start = stroke[i - 1]
                    val end = stroke[i]
                    canvas.drawLine(
                        start.first * scaleFactor,
                        start.second * scaleFactor,
                        end.first * scaleFactor,
                        end.second * scaleFactor,
                        strokePaint
                    )
                }
            }
        }
    }

    if (transform.overlayText.isNotBlank()) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
        }
        canvas.drawText(transform.overlayText.take(40), canvasSize / 2f, canvasSize - 40f, textPaint)
    }

    return ByteArrayOutputStream().use { out ->
        output.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}

fun createStickerTitleFromText(title: String?): String {
    val clean = title?.trim().orEmpty()
    return if (clean.isBlank()) "Sticker" else clean.take(60)
}
