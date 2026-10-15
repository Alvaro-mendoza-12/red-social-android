package conectate.com.presentation.raffles

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import conectate.com.data.raffles.RaffleParticipant

// The wheel has its own vivid palette even when the app uses a monochrome theme.
internal val raffleWheelColors = listOf(Color(0xFFD92D45), Color(0xFF245BDD), Color(0xFF13795B),
    Color(0xFFB85C00), Color(0xFF7734BF), Color(0xFF087E8B))

internal val raffleSpinEasing = CubicBezierEasing(.12f, 0f, .08f, 1f)

/** Segment zero begins at the fixed top pointer; probability is never calculated here. */
fun wheelTargetRotation(current: Float, winnerIndex: Int, count: Int): Float {
    require(count > 0 && winnerIndex in 0 until count)
    val base = (current % 360f + 360f) % 360f
    val target = (360f - (winnerIndex + .5f) * 360f / count) % 360f
    val delta = (target - base + 360f) % 360f
    return base + 1440f + delta
}

@Composable
fun RaffleWheel(participants: List<RaffleParticipant>, winnerId: String? = null, animate: Boolean = false,
    animationKey: String? = winnerId,
    rotationState: Animatable<Float, AnimationVector1D>? = null,
    onAnimationFinished: () -> Unit = {}) {
    val rotation = remember { Animatable(0f) }
    val finish by rememberUpdatedState(onAnimationFinished)
    LaunchedEffect(animationKey, animate) {
        if (rotationState == null && animate && winnerId != null) {
            val index = participants.indexOfFirst { it.id == winnerId }
            if (index >= 0) {
                // Equivalent visual orientation, bounded float precision across thousands of draws/rounds.
                rotation.snapTo((rotation.value % 360f + 360f) % 360f)
                rotation.animateTo(wheelTargetRotation(rotation.value, index, participants.size), tween(4000, easing = raffleSpinEasing))
            }
            finish()
        }
    }
    val outline = MaterialTheme.colorScheme.onSurface
    val hub = MaterialTheme.colorScheme.surface
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Canvas(Modifier.size(280.dp).testTag("raffle_wheel").semantics {
            contentDescription = "Ruleta con ${participants.size} participantes. El servidor determina los resultados."
            stateDescription = if (animate) "Girando" else "En reposo"
        }) {
            val inset = 12.dp.toPx()
            val diameter = size.minDimension - inset * 2
            drawCircle(outline.copy(alpha = .12f), diameter / 2 + 5.dp.toPx())
            if (participants.isEmpty()) { drawCircle(outline.copy(alpha = .12f), diameter / 2); return@Canvas }
            val slice = 360f / participants.size
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 11.dp.toPx(); isFakeBoldText = true }
            rotate(rotationState?.value ?: rotation.value) {
                participants.forEachIndexed { index, p ->
                    drawArc(raffleWheelColors[index % raffleWheelColors.size], -90f + index * slice, slice, true,
                        topLeft = Offset(inset, inset), size = Size(diameter, diameter))
                    if (participants.size <= 50) drawArc(Color.White.copy(alpha = .25f), -90f + index * slice, slice, true,
                        topLeft = Offset(inset, inset), size = Size(diameter, diameter), style = Stroke(1.dp.toPx()))
                    if (participants.size <= 50) {
                        val label = if (participants.size <= 12) p.name.take(13) else (index + 1).toString()
                        paint.color = Color.White.toArgb()
                        val canvas = drawContext.canvas.nativeCanvas
                        canvas.save()
                        canvas.rotate(index * slice + slice / 2, center.x, center.y)
                        canvas.drawText(label, center.x, center.y - diameter * .30f, paint)
                        canvas.restore()
                    }
                }
            }
            drawCircle(Color.White.copy(alpha = .5f), diameter / 2, style = Stroke(2.dp.toPx()))
            drawCircle(outline.copy(alpha = .15f), 23.dp.toPx(), Offset(center.x, center.y + 2.dp.toPx()))
            drawCircle(hub, 22.dp.toPx())
            drawCircle(Color(0xFFF2B84B), 8.dp.toPx())
            val pointer = Path().apply {
                moveTo(center.x - 10.dp.toPx(), 2.dp.toPx())
                lineTo(center.x + 10.dp.toPx(), 2.dp.toPx())
                lineTo(center.x, 23.dp.toPx())
                close()
            }
            // A small pointer tick follows visual sector boundaries, without changing the server winner.
            val degrees = rotationState?.value ?: rotation.value
            val phase = ((degrees / slice) % 1f + 1f) % 1f
            val tick = if (animate) kotlin.math.sin(phase * kotlin.math.PI).toFloat() * 7f else 0f
            rotate(-tick, Offset(center.x, 2.dp.toPx())) {
                drawPath(pointer, outline)
                drawPath(pointer, hub, style = Stroke(1.5.dp.toPx()))
            }
        }
        if (participants.size > 12) Text("Todos los segmentos tienen la misma probabilidad. Consulta los nombres en la lista.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
    }
}
