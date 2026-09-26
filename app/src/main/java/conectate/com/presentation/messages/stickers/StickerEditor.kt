package conectate.com.presentation.messages.stickers

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private enum class StickerEditMode {
    MOVE,
    DRAW,
    ERASE
}

private data class StickerEditorSnapshot(
    val bitmap: android.graphics.Bitmap?,
    val transform: StickerEditorTransform,
    val overlayText: String,
    val overlayEmoji: String,
    val drawStrokes: List<List<Offset>>
)

@Composable
fun StickerEditor(
    imageUri: Uri,
    onDismiss: () -> Unit,
    onSave: (bytes: ByteArray, title: String) -> Unit,
    isSaving: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadedBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, imageUri) {
        value = loadStickerBitmapFromUri(context, imageUri)
    }

    var workingBitmap by remember(imageUri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var transform by remember(imageUri) { mutableStateOf(StickerEditorTransform()) }
    var title by remember(imageUri) { mutableStateOf("") }
    var overlayText by remember(imageUri) { mutableStateOf("") }
    var overlayEmoji by remember(imageUri) { mutableStateOf("") }
    var isAutoCutoutLoading by remember(imageUri) { mutableStateOf(false) }
    var editorError by remember(imageUri) { mutableStateOf<String?>(null) }
    var editMode by remember(imageUri) { mutableStateOf(StickerEditMode.MOVE) }
    val drawStrokes = remember(imageUri) { mutableStateListOf<List<Offset>>() }
    var activeStroke by remember(imageUri) { mutableStateOf<List<Offset>>(emptyList()) }
    val undoStack = remember(imageUri) { mutableStateListOf<StickerEditorSnapshot>() }
    val redoStack = remember(imageUri) { mutableStateListOf<StickerEditorSnapshot>() }

    fun createSnapshot(): StickerEditorSnapshot {
        return StickerEditorSnapshot(
            bitmap = workingBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, true),
            transform = transform,
            overlayText = overlayText,
            overlayEmoji = overlayEmoji,
            drawStrokes = drawStrokes.map { it.toList() }
        )
    }

    fun pushUndoSnapshot() {
        undoStack.add(createSnapshot())
        if (undoStack.size > 30) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    fun restoreSnapshot(snapshot: StickerEditorSnapshot) {
        workingBitmap = snapshot.bitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        transform = snapshot.transform
        overlayText = snapshot.overlayText
        overlayEmoji = snapshot.overlayEmoji
        drawStrokes.clear()
        drawStrokes.addAll(snapshot.drawStrokes.map { it.toList() })
        activeStroke = emptyList()
    }

    LaunchedEffect(loadedBitmap) {
        if (workingBitmap == null && loadedBitmap != null) {
            workingBitmap = loadedBitmap
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                    Text(
                        text = "Editor de sticker",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (undoStack.isEmpty()) return@IconButton
                                val current = createSnapshot()
                                val snapshot = undoStack.removeAt(undoStack.lastIndex)
                                redoStack.add(current)
                                restoreSnapshot(snapshot)
                            },
                            enabled = !isSaving && undoStack.isNotEmpty()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Deshacer")
                        }
                        IconButton(
                            onClick = {
                                if (redoStack.isEmpty()) return@IconButton
                                val current = createSnapshot()
                                val snapshot = redoStack.removeAt(redoStack.lastIndex)
                                undoStack.add(current)
                                restoreSnapshot(snapshot)
                            },
                            enabled = !isSaving && redoStack.isNotEmpty()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Rehacer")
                        }
                        FilledTonalButton(
                            enabled = !isSaving && workingBitmap != null,
                            onClick = {
                                val bitmap = workingBitmap ?: return@FilledTonalButton
                                val bytes = exportStickerPng(
                                    source = bitmap,
                                    transform = transform.copy(
                                        overlayText = overlayText,
                                        overlayEmoji = overlayEmoji
                                    ),
                                    drawStrokes = (drawStrokes.toList() + listOf(activeStroke))
                                        .filter { it.size > 1 }
                                        .map { stroke -> stroke.map { point -> point.x to point.y } },
                                    previewSizePx = 320f
                                )
                                onSave(bytes, createStickerTitleFromText(title))
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F172A)),
                    contentAlignment = Alignment.Center
                ) {
                    CheckerboardBackground()

                    val bitmap = workingBitmap
                    if (bitmap == null) {
                        CircularProgressIndicator()
                    } else {
                        Box(
                            modifier = Modifier
                                .size(320.dp)
                                .pointerInput(Unit) {
                                    if (editMode == StickerEditMode.DRAW) {
                                        detectDragGestures(
                                            onDragStart = { start ->
                                                pushUndoSnapshot()
                                                activeStroke = listOf(start)
                                            },
                                            onDragEnd = {
                                                if (activeStroke.size > 1) {
                                                    drawStrokes.add(activeStroke)
                                                }
                                                activeStroke = emptyList()
                                            },
                                            onDragCancel = {
                                                activeStroke = emptyList()
                                            }
                                        ) { change, _ ->
                                            activeStroke = activeStroke + change.position
                                        }
                                    } else if (editMode == StickerEditMode.ERASE) {
                                        detectDragGestures(
                                            onDragStart = { start ->
                                                pushUndoSnapshot()
                                                val bitmap = workingBitmap ?: return@detectDragGestures
                                                workingBitmap = eraseFromBitmap(bitmap, start)
                                            }
                                        ) { change, _ ->
                                            val bitmap = workingBitmap ?: return@detectDragGestures
                                            workingBitmap = eraseFromBitmap(bitmap, change.position)
                                        }
                                    } else {
                                        detectTransformGestures { _, pan, zoom, rotation ->
                                            transform = transform.copy(
                                                scale = (transform.scale * zoom).coerceIn(0.3f, 4f),
                                                rotation = transform.rotation + rotation * 57.2958f,
                                                offsetX = (transform.offsetX + pan.x).coerceIn(-380f, 380f),
                                                offsetY = (transform.offsetY + pan.y).coerceIn(-380f, 380f)
                                            )
                                        }
                                    }
                                }
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Sticker preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = transform.offsetX
                                        translationY = transform.offsetY
                                        rotationZ = transform.rotation
                                    }
                                    .scale(transform.scale)
                            )
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 5.dp.toPx()
                                drawStrokes.forEach { stroke ->
                                    if (stroke.size > 1) {
                                        for (i in 1 until stroke.size) {
                                            drawLine(
                                                color = Color.White,
                                                start = stroke[i - 1],
                                                end = stroke[i],
                                                strokeWidth = strokeWidth,
                                                cap = StrokeCap.Round
                                            )
                                        }
                                    }
                                }
                                if (activeStroke.size > 1) {
                                    for (i in 1 until activeStroke.size) {
                                        drawLine(
                                            color = Color.White,
                                            start = activeStroke[i - 1],
                                            end = activeStroke[i],
                                            strokeWidth = strokeWidth,
                                            cap = StrokeCap.Round
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val bitmap = workingBitmap ?: return@FilledTonalButton
                            if (isAutoCutoutLoading) return@FilledTonalButton
                            pushUndoSnapshot()
                            isAutoCutoutLoading = true
                            editorError = null
                            scope.launch {
                                val result = removeStickerBackgroundAi(bitmap)
                                isAutoCutoutLoading = false
                                result
                                    .onSuccess { cutout -> workingBitmap = cutout }
                                    .onFailure { error ->
                                        editorError = error.message ?: "No se pudo aplicar recorte automático"
                                    }
                            }
                        },
                        enabled = !isSaving && !isAutoCutoutLoading && workingBitmap != null
                    ) {
                        if (isAutoCutoutLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                            Text("Auto recorte IA")
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            pushUndoSnapshot()
                            transform = StickerEditorTransform()
                            overlayText = ""
                            overlayEmoji = ""
                            drawStrokes.clear()
                            activeStroke = emptyList()
                            editMode = StickerEditMode.MOVE
                        },
                        enabled = !isSaving
                    ) {
                        Text("Reset")
                    }

                    FilledTonalButton(
                        onClick = { editMode = StickerEditMode.MOVE },
                        enabled = !isSaving
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Text(if (editMode == StickerEditMode.MOVE) "Mover" else "Modo mover")
                    }

                    FilledTonalButton(
                        onClick = { editMode = StickerEditMode.DRAW },
                        enabled = !isSaving
                    ) {
                        Text(if (editMode == StickerEditMode.DRAW) "Dibujar" else "Modo dibujo")
                    }

                    FilledTonalButton(
                        onClick = { editMode = StickerEditMode.ERASE },
                        enabled = !isSaving
                    ) {
                        Text(if (editMode == StickerEditMode.ERASE) "Recorte manual" else "Modo recorte")
                    }
                }

                editorError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(60) },
                    label = { Text("Nombre del sticker") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                OutlinedTextField(
                    value = overlayText,
                    onValueChange = { overlayText = it.take(40) },
                    label = { Text("Texto opcional") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("😀", "😂", "🔥", "❤️", "😎", "🥳").forEach { emoji ->
                        AssistChip(
                            onClick = { overlayEmoji = emoji },
                            label = { Text(emoji) }
                        )
                    }
                }

                Text(
                    text = "Escala",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Slider(
                    value = transform.scale,
                    onValueChange = { transform = transform.copy(scale = it) },
                    valueRange = 0.3f..4f
                )

                Text(
                    text = "Rotación: usa gesto de dos dedos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CheckerboardBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val square = 30f
        val dark = Color(0xFF0B1220)
        val light = Color(0xFF111827)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = row % 2
            while (x < size.width) {
                drawRect(
                    color = if (col % 2 == 0) dark else light,
                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(square, square)
                )
                x += square
                col++
            }
            y += square
            row++
        }
    }
}

private fun eraseFromBitmap(bitmap: android.graphics.Bitmap, point: Offset): android.graphics.Bitmap {
    val mutable = if (bitmap.isMutable) bitmap else bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(mutable)
    val x = (point.x / 320f * mutable.width.toFloat()).coerceIn(0f, mutable.width.toFloat())
    val y = (point.y / 320f * mutable.height.toFloat()).coerceIn(0f, mutable.height.toFloat())
    val radius = (22f / 320f) * mutable.width.toFloat().coerceAtLeast(1f)
    val eraserPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    canvas.drawCircle(x, y, radius, eraserPaint)
    return mutable
}
