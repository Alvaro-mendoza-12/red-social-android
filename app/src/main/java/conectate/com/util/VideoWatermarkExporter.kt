package conectate.com.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import conectate.com.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object VideoWatermarkExporter {
    suspend fun exportWithBrandWatermark(
        context: Context,
        inputFile: File,
        userName: String
    ): Result<File> {
        return try {
            val outputFile = File(
                context.cacheDir,
                "watermarked_${System.currentTimeMillis()}.mp4"
            )
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val watermarkBitmap = withContext(Dispatchers.Default) {
                createWatermarkBitmap(context, userName)
            }
            val settings = OverlaySettings.Builder()
                .setOverlayFrameAnchor(-1f, -1f)
                .setBackgroundFrameAnchor(-0.94f, -0.90f)
                .setScale(0.86f, 0.86f)
                .build()
            val overlay = BitmapOverlay.createStaticBitmapOverlay(watermarkBitmap, settings)
            val overlays: List<TextureOverlay> = listOf(overlay)
            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inputFile)))
                .setEffects(
                    Effects(
                        emptyList<AudioProcessor>(),
                        listOf(OverlayEffect(overlays))
                    )
                )
                .build()

            val request = TransformationRequest.Builder()
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()

            val finished = CompletableDeferred<Unit>()
            var failure: Throwable? = null
            withContext(Dispatchers.Main.immediate) {
                val transformer = Transformer.Builder(context)
                    .setTransformationRequest(request)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: androidx.media3.transformer.Composition,
                            result: ExportResult
                        ) {
                            finished.complete(Unit)
                        }

                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            result: ExportResult,
                            exception: androidx.media3.transformer.ExportException
                        ) {
                            failure = exception
                            finished.complete(Unit)
                        }
                    })
                    .build()

                transformer.start(editedMediaItem, outputFile.absolutePath)
                val progressHolder = ProgressHolder()
                while (!finished.isCompleted) {
                    transformer.getProgress(progressHolder)
                    delay(180)
                }
            }

            watermarkBitmap.recycle()

            if (failure != null) {
                outputFile.delete()
                return Result.failure(
                    Exception("No se pudo aplicar la marca de agua al video descargado.", failure)
                )
            }
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                return Result.failure(Exception("La exportación del video con marca de agua falló."))
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun createWatermarkBitmap(context: Context, userName: String): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (220 * density).toInt()
        val height = (68 * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val iconSize = (28 * density).toInt()
        val padding = 10f * density
        val top = 8f * density

        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
        if (icon != null) {
            icon.setBounds(
                padding.toInt(),
                top.toInt(),
                (padding + iconSize).toInt(),
                (top + iconSize).toInt()
            )
            icon.draw(canvas)
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
            isFakeBoldText = true
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10f * density
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }

        val textStartX = padding + iconSize + (8f * density)
        canvas.drawText("Novvin", textStartX, 24f * density, titlePaint)
        canvas.drawText("@${sanitizeUserName(userName)}", textStartX, 42f * density, userPaint)

        return bitmap
    }

    private fun sanitizeUserName(value: String): String {
        return value.trim().ifBlank { "usuario" }.take(28)
    }
}
