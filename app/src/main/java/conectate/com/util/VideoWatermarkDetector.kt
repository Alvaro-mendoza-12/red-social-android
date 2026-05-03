package conectate.com.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

data class VideoWatermarkDetection(
    val brand: String,
    val evidence: String
)

object VideoWatermarkDetector {
    private val keywordPatterns = listOf(
        listOf("tiktok", "tik tok") to "TikTok",
        listOf("capcut", "cap cut") to "CapCut",
        listOf("instagram", "reels", "ig reels") to "Instagram Reels",
        listOf("facebook", "fb") to "Facebook",
        listOf("kwai") to "Kwai",
        listOf("likee") to "Likee",
        listOf("snackvideo", "snack video") to "SnackVideo",
        listOf("youtube shorts", "youtube", "shorts") to "YouTube Shorts"
    )

    suspend fun detect(context: Context, videoUri: Uri): VideoWatermarkDetection? {
        return withContext(Dispatchers.Default) {
            detectFromDisplayName(context, videoUri) ?: detectFromFrames(context, videoUri)
        }
    }

    private fun detectFromDisplayName(context: Context, videoUri: Uri): VideoWatermarkDetection? {
        val displayName = resolveDisplayName(context, videoUri)
        val brand = findBrand(normalize(displayName)) ?: return null
        return VideoWatermarkDetection(brand = brand, evidence = displayName)
    }

    private suspend fun detectFromFrames(context: Context, videoUri: Uri): VideoWatermarkDetection? {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val frames = extractCandidateFrames(context, videoUri)
            for (frame in frames) {
                for (crop in buildInspectionCrops(frame)) {
                    val recognizedText = runCatching {
                        recognizer.processAwait(InputImage.fromBitmap(crop, 0)).text
                    }.getOrElse { error ->
                        Log.w("VideoWatermarkDetector", "OCR failed for one crop", error)
                        ""
                    }

                    val brand = findBrand(normalize(recognizedText))
                    if (brand != null) {
                        return VideoWatermarkDetection(
                            brand = brand,
                            evidence = recognizedText.take(120).ifBlank { "OCR" }
                        )
                    }
                }
            }
            null
        } finally {
            recognizer.close()
        }
    }

    private fun extractCandidateFrames(context: Context, videoUri: Uri): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1_000L)
                ?: 4_000L

            val positionsUs = listOf(150L, 500L, 850L)
                .map { percentage ->
                    ((durationMs * percentage) / 1_000L) * 1_000L
                }
                .distinct()

            positionsUs.mapNotNull { positionUs ->
                retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        } catch (error: Exception) {
            Log.e("VideoWatermarkDetector", "Error extracting frames", error)
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun buildInspectionCrops(bitmap: Bitmap): List<Bitmap> {
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val crops = listOf(
            Rect(0, 0, width, max((height * 0.18f).toInt(), 1)),
            Rect(0, max((height * 0.62f).toInt(), 0), max((width * 0.38f).toInt(), 1), height),
            Rect(max((width * 0.62f).toInt(), 0), max((height * 0.62f).toInt(), 0), width, height),
            Rect(0, max((height * 0.76f).toInt(), 0), width, height)
        )

        return crops.mapNotNull { rect ->
            val cropWidth = (rect.right - rect.left).coerceAtLeast(1)
            val cropHeight = (rect.bottom - rect.top).coerceAtLeast(1)
            runCatching {
                Bitmap.createBitmap(bitmap, rect.left, rect.top, cropWidth, cropHeight)
            }.getOrNull()
        }
    }

    private fun resolveDisplayName(context: Context, videoUri: Uri): String {
        val fromQuery = runCatching {
            context.contentResolver.query(
                videoUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
        }.getOrNull()

        return when {
            !fromQuery.isNullOrBlank() -> fromQuery
            !videoUri.lastPathSegment.isNullOrBlank() -> videoUri.lastPathSegment.orEmpty()
            else -> videoUri.toString()
        }
    }

    private fun findBrand(normalizedText: String): String? {
        if (normalizedText.isBlank()) return null
        return keywordPatterns.firstOrNull { (tokens, _) ->
            tokens.any { token -> normalizedText.contains(token) }
        }?.second
    }

    private fun normalize(raw: String?): String {
        return raw
            .orEmpty()
            .lowercase()
            .replace('á', 'a')
            .replace('é', 'e')
            .replace('í', 'i')
            .replace('ó', 'o')
            .replace('ú', 'u')
            .replace(Regex("[^a-z0-9@ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

private suspend fun TextRecognizer.processAwait(image: InputImage): Text {
    return suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
    }
}
