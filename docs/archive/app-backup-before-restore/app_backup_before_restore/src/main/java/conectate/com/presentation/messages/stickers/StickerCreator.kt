package conectate.com.presentation.messages.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.WorkerThread
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max

@WorkerThread
suspend fun loadStickerBitmapFromUri(
    context: Context,
    uri: Uri,
    maxDimension: Int = 1400
): Bitmap? = withContext(Dispatchers.IO) {
    val decoded = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }.getOrNull() ?: return@withContext null

    val largest = max(decoded.width, decoded.height)
    if (largest <= maxDimension) return@withContext decoded
    val ratio = maxDimension.toFloat() / largest.toFloat()
    Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * ratio).toInt().coerceAtLeast(1),
        (decoded.height * ratio).toInt().coerceAtLeast(1),
        true
    )
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
    return if (clean.isBlank()) "Sticker personalizado" else clean.take(60)
}
