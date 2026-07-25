package conectate.com.presentation.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

@Composable
fun MediaCaptionPreviewDialog(
    mediaUri: Uri,
    mediaType: String,
    caption: String,
    onCaptionChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Uri, String) -> Unit,
    isSending: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isVideo = mediaType == "video" || mediaType == "view_once_video"
    val isViewOnce = mediaType == "view_once_image" || mediaType == "view_once_video"
    val title = if (isVideo) "Vista previa de video" else "Vista previa de imagen"

    var rotateQuarterTurns by remember(mediaUri) { mutableStateOf(0) }
    var brightness by remember(mediaUri) { mutableStateOf(0f) }
    var contrast by remember(mediaUri) { mutableStateOf(1f) }
    var cropMode by remember(mediaUri) { mutableStateOf(ImageCropMode.ORIGINAL) }
    var drawEnabled by remember(mediaUri) { mutableStateOf(false) }
    var overlayText by remember(mediaUri) { mutableStateOf("") }
    var overlayEmoji by remember(mediaUri) { mutableStateOf("") }
    var overlaySticker by remember(mediaUri) { mutableStateOf("") }
    var preparing by remember(mediaUri) { mutableStateOf(false) }
    var localError by remember(mediaUri) { mutableStateOf<String?>(null) }
    var previewSize by remember(mediaUri) { mutableStateOf(IntSize.Zero) }
    val doodleStrokes = remember(mediaUri) { mutableStateListOf<MutableList<Offset>>() }
    var activeStrokeIndex by remember(mediaUri) { mutableStateOf(-1) }

    val composeMatrix = remember(brightness, contrast) {
        ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness * 255f,
                0f, contrast, 0f, 0f, brightness * 255f,
                0f, 0f, contrast, 0f, brightness * 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
    val previewHeight = when (cropMode) {
        ImageCropMode.ORIGINAL -> 220.dp
        ImageCropMode.SQUARE -> 260.dp
        ImageCropMode.PORTRAIT_4_5 -> 300.dp
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    SubcomposeAsyncImage(
                        model = mediaUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { previewSize = it }
                            .drawWithContent {
                                drawContent()
                                if (!isVideo) {
                                    doodleStrokes.forEach { stroke ->
                                        for (i in 1 until stroke.size) {
                                            drawLine(
                                                color = Color(0xFFFF4D4D),
                                                start = stroke[i - 1],
                                                end = stroke[i],
                                                strokeWidth = 5f,
                                                cap = StrokeCap.Round
                                            )
                                        }
                                    }
                                }
                            }
                            .pointerInput(drawEnabled, isVideo) {
                                if (!drawEnabled || isVideo) return@pointerInput
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val newStroke = mutableListOf(offset)
                                        doodleStrokes.add(newStroke)
                                        activeStrokeIndex = doodleStrokes.lastIndex
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        if (activeStrokeIndex in doodleStrokes.indices) {
                                            doodleStrokes[activeStrokeIndex].add(change.position)
                                        }
                                    },
                                    onDragEnd = { activeStrokeIndex = -1 },
                                    onDragCancel = { activeStrokeIndex = -1 }
                                )
                            },
                        colorFilter = if (!isVideo) ColorFilter.colorMatrix(composeMatrix) else null,
                        contentScale = ContentScale.Fit,
                        loading = { CircularProgressIndicator() }
                    )
                    if (isVideo) {
                        Icon(
                            imageVector = Icons.Default.PlayCircleOutline,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                if (isViewOnce) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Se abrirá una sola vez",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                if (!isVideo) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { rotateQuarterTurns = (rotateQuarterTurns + 1) % 4 },
                            label = { Text("Girar 90°") },
                            leadingIcon = { Icon(Icons.Default.RotateRight, contentDescription = null) }
                        )
                        AssistChip(
                            onClick = { drawEnabled = !drawEnabled },
                            label = { Text(if (drawEnabled) "Dibujo ON" else "Dibujo OFF") },
                            leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null) }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { cropMode = ImageCropMode.ORIGINAL }, label = { Text("Original") })
                        AssistChip(onClick = { cropMode = ImageCropMode.SQUARE }, label = { Text("1:1") })
                        AssistChip(onClick = { cropMode = ImageCropMode.PORTRAIT_4_5 }, label = { Text("4:5") })
                    }

                    Text("Brillo: ${(brightness * 100f).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -0.35f..0.35f)

                    Text("Contraste: ${(contrast * 100f).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.7f..1.4f)
                }

                if (!isVideo) {
                    OutlinedTextField(
                        value = overlayText,
                        onValueChange = { overlayText = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Texto sobre imagen (opcional)") },
                        maxLines = 2
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = overlayEmoji,
                            onValueChange = { overlayEmoji = it.take(6) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Emoji") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = overlaySticker,
                            onValueChange = { overlaySticker = it.take(32) },
                            modifier = Modifier.weight(1f),
                            label = { Text("Sticker/Tag") },
                            singleLine = true
                        )
                    }
                } else {
                    Text(
                        text = "El video se enviará completo. Usa el editor de Videos para recortar o aplicar efectos reales.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = caption,
                    onValueChange = onCaptionChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Agregar texto (opcional)") },
                    maxLines = 3
                )

                if (!localError.isNullOrBlank()) {
                    Text(
                        text = localError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isSending || preparing) return@TextButton
                    val outgoingCaption = caption.trim()

                    if (isVideo) {
                        onConfirm(mediaUri, outgoingCaption)
                        return@TextButton
                    }

                    localError = null
                    preparing = true
                    val normalizedStrokes = normalizeStrokes(doodleStrokes, previewSize)
                    scope.launch {
                        val processedUri = withContext(Dispatchers.Default) {
                            runCatching {
                                processImageForSending(
                                    context = context,
                                    sourceUri = mediaUri,
                                    cropMode = cropMode,
                                    rotateQuarterTurns = rotateQuarterTurns,
                                    brightness = brightness,
                                    contrast = contrast,
                                    overlayText = overlayText,
                                    overlayEmoji = overlayEmoji,
                                    overlaySticker = overlaySticker,
                                    doodleStrokes = normalizedStrokes
                                )
                            }.getOrElse { mediaUri }
                        }
                        preparing = false
                        onConfirm(processedUri, outgoingCaption)
                    }
                },
                enabled = !isSending && !preparing
            ) {
                if (isSending || preparing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Enviar")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending && !preparing) {
                Text("Cancelar")
            }
        }
    )
}

private enum class ImageCropMode {
    ORIGINAL,
    SQUARE,
    PORTRAIT_4_5
}

private fun normalizeStrokes(
    strokes: List<List<Offset>>,
    size: IntSize
): List<List<Offset>> {
    if (size.width <= 0 || size.height <= 0) return emptyList()
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    return strokes.map { stroke ->
        stroke.map { p ->
            Offset(
                x = (p.x / w).coerceIn(0f, 1f),
                y = (p.y / h).coerceIn(0f, 1f)
            )
        }
    }
}

private fun processImageForSending(
    context: Context,
    sourceUri: Uri,
    cropMode: ImageCropMode,
    rotateQuarterTurns: Int,
    brightness: Float,
    contrast: Float,
    overlayText: String,
    overlayEmoji: String,
    overlaySticker: String,
    doodleStrokes: List<List<Offset>>
): Uri {
    val decoded = decodeBitmap(context, sourceUri) ?: return sourceUri
    val cropped = cropBitmap(decoded, cropMode)
    val rotated = rotateBitmap(cropped, rotateQuarterTurns)
    val toned = applyBrightnessContrast(rotated, brightness, contrast)
    val merged = drawOverlays(
        bitmap = toned,
        overlayText = overlayText,
        overlayEmoji = overlayEmoji,
        overlaySticker = overlaySticker,
        doodleStrokes = doodleStrokes
    )
    return saveBitmapToCache(context, merged)
}

private fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
    val maxDimension = 4096
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = true
                val width = info.size.width
                val height = info.size.height
                val largest = maxOf(width, height)
                if (largest > maxDimension) {
                    val scale = maxDimension.toFloat() / largest.toFloat()
                    decoder.setTargetSize(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        }
    }.getOrNull()
}

private fun cropBitmap(bitmap: Bitmap, mode: ImageCropMode): Bitmap {
    if (mode == ImageCropMode.ORIGINAL) return bitmap
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return bitmap

    val ratio = when (mode) {
        ImageCropMode.SQUARE -> 1f
        ImageCropMode.PORTRAIT_4_5 -> 4f / 5f
        ImageCropMode.ORIGINAL -> w.toFloat() / h.toFloat()
    }
    val current = w.toFloat() / h.toFloat()
    val cropW: Int
    val cropH: Int
    if (current > ratio) {
        cropH = h
        cropW = (h * ratio).toInt().coerceAtLeast(1)
    } else {
        cropW = w
        cropH = (w / ratio).toInt().coerceAtLeast(1)
    }
    val left = ((w - cropW) / 2).coerceAtLeast(0)
    val top = ((h - cropH) / 2).coerceAtLeast(0)
    return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
}

private fun rotateBitmap(bitmap: Bitmap, quarterTurns: Int): Bitmap {
    val turns = ((quarterTurns % 4) + 4) % 4
    if (turns == 0) return bitmap
    val matrix = Matrix().apply { postRotate((turns * 90).toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun applyBrightnessContrast(bitmap: Bitmap, brightness: Float, contrast: Float): Bitmap {
    if (brightness == 0f && contrast == 1f) return bitmap
    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(out)
    val translate = brightness * 255f
    val matrix = AndroidColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(matrix)
    }
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    return out
}

private fun drawOverlays(
    bitmap: Bitmap,
    overlayText: String,
    overlayEmoji: String,
    overlaySticker: String,
    doodleStrokes: List<List<Offset>>
): Bitmap {
    val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = AndroidCanvas(mutable)
    val w = mutable.width.toFloat()
    val h = mutable.height.toFloat()

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = max(28f, w * 0.045f)
        setShadowLayer(8f, 0f, 0f, AndroidColor.BLACK)
    }

    var y = h * 0.12f
    val x = w * 0.06f
    if (overlayEmoji.isNotBlank()) {
        canvas.drawText(overlayEmoji.trim(), x, y, textPaint)
        y += textPaint.textSize * 1.25f
    }
    if (overlayText.isNotBlank()) {
        overlayText.trim().lineSequence().take(2).forEach { line ->
            canvas.drawText(line.take(80), x, y, textPaint)
            y += textPaint.textSize * 1.1f
        }
    }
    if (overlaySticker.isNotBlank()) {
        val tag = "🧩 ${overlaySticker.trim().take(32)}"
        canvas.drawText(tag, x, y, textPaint)
    }

    val doodlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FF4D4D")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = max(6f, w * 0.008f)
    }

    doodleStrokes.forEach { stroke ->
        for (i in 1 until stroke.size) {
            val p0 = stroke[i - 1]
            val p1 = stroke[i]
            canvas.drawLine(
                p0.x * w,
                p0.y * h,
                p1.x * w,
                p1.y * h,
                doodlePaint
            )
        }
    }

    return mutable
}

private fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri {
    val file = File(
        context.cacheDir,
        "media_edit_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"
    )
    FileOutputStream(file).use { out ->
        flattenBitmapForJpeg(bitmap).compress(Bitmap.CompressFormat.JPEG, 93, out)
    }
    return file.toUri()
}

private fun flattenBitmapForJpeg(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(flattened)
    canvas.drawColor(AndroidColor.WHITE)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    return flattened
}
