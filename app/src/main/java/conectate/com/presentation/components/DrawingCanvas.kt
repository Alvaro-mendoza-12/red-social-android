package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp

import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable

@Serializable
data class Line(
    val points: List<Pair<Float, Float>>,
    val color: Int,
    val strokeWidth: Float
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DrawingCanvas(
    onSave: (LineList: List<Line>) -> Unit,
    modifier: Modifier = Modifier
) {
    var lines = remember { mutableStateListOf<Line>() }
    var currentLine = remember { mutableStateListOf<Pair<Float, Float>>() }
    var currentColor by remember { mutableStateOf(Color.Black) }
    var currentStrokeWidth by remember { mutableStateOf(5f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
    ) {
        // Toolbar superior de la pizarra
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { if (lines.isNotEmpty()) lines.removeAt(lines.size - 1) }) {
                    Icon(Icons.Default.Undo, contentDescription = "Deshacer", tint = Color.DarkGray)
                }
                IconButton(onClick = { lines.clear() }) {
                    Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = Color.DarkGray)
                }
            }

            // Selector de colores rápido
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(Color.Black, Color.Red, Color.Blue, Color.Green).forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(color)
                            .padding(if (currentColor == color) 2.dp else 0.dp)
                            .background(if (currentColor == color) Color.LightGray else color)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { currentColor = color }
                    )
                }
            }
        }

        Divider(color = Color.LightGray, thickness = 0.5.dp)

        // El lienzo de dibujo
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInteropFilter { event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            currentLine.add(Pair(event.x, event.y))
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            currentLine.add(Pair(event.x, event.y))
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            lines.add(Line(currentLine.toList(), currentColor.toArgb(), currentStrokeWidth))
                            currentLine.clear()
                            true
                        }
                        else -> false
                    }
                }
        ) {
            // Dibujar líneas guardadas
            lines.forEach { line ->
                val path = Path().apply {
                    if (line.points.isNotEmpty()) {
                        moveTo(line.points[0].first, line.points[0].second)
                        line.points.forEach { lineTo(it.first, it.second) }
                    }
                }
                drawPath(
                    path = path,
                    color = Color(line.color),
                    style = Stroke(
                        width = line.strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Dibujar línea actual
            val currentPath = Path().apply {
                if (currentLine.isNotEmpty()) {
                    moveTo(currentLine[0].first, currentLine[0].second)
                    currentLine.forEach { lineTo(it.first, it.second) }
                }
            }
            drawPath(
                path = currentPath,
                color = currentColor,
                style = Stroke(
                    width = currentStrokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
        
        // Footer con botón de enviar
        Button(
            onClick = { onSave(lines.toList()) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("ENVIAR DIBUJO")
        }
    }
}

// Extensiones útiles
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.clickable(onClick: () -> Unit) = this.pointerInteropFilter {
    if (it.action == MotionEvent.ACTION_UP) {
        onClick()
    }
    true
}
