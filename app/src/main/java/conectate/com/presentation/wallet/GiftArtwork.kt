package conectate.com.presentation.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val NovvinPink = Color(0xFFFF4F87)
private val NovvinPurple = Color(0xFF8F67FF)
private val NovvinCyan = Color(0xFF35D7FF)
private val NovvinGold = Color(0xFFFFC857)

@Composable
fun GiftArtwork(
    giftId: String,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val unit = size.minDimension / 100f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                center = center,
                radius = 49f * unit
            ),
            radius = 49f * unit,
            center = center
        )

        when (giftId) {
            "builtin-rose" -> {
                repeat(8) { index ->
                    rotate(index * 45f, center) {
                        drawOval(
                            brush = Brush.linearGradient(listOf(NovvinPink, NovvinPurple)),
                            topLeft = Offset(center.x - 10f * unit, center.y - 39f * unit),
                            size = Size(20f * unit, 36f * unit)
                        )
                    }
                }
                drawCircle(NovvinGold, 12f * unit, center)
                drawCircle(Color.White.copy(alpha = 0.55f), 5f * unit, center - Offset(3f * unit, 3f * unit))
            }
            "builtin-trophy" -> {
                val cup = Path().apply {
                    moveTo(center.x - 27f * unit, center.y - 30f * unit)
                    lineTo(center.x + 27f * unit, center.y - 30f * unit)
                    cubicTo(center.x + 24f * unit, center.y + 2f * unit, center.x + 14f * unit, center.y + 15f * unit, center.x, center.y + 18f * unit)
                    cubicTo(center.x - 14f * unit, center.y + 15f * unit, center.x - 24f * unit, center.y + 2f * unit, center.x - 27f * unit, center.y - 30f * unit)
                    close()
                }
                drawPath(cup, Brush.linearGradient(listOf(NovvinGold, Color(0xFFFF8F3D))))
                drawLine(NovvinGold, center + Offset(0f, 16f * unit), center + Offset(0f, 34f * unit), 8f * unit, StrokeCap.Round)
                drawRoundRect(NovvinGold, Offset(center.x - 22f * unit, center.y + 31f * unit), Size(44f * unit, 9f * unit), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * unit))
                drawArc(NovvinGold, 85f, 190f, false, Offset(center.x - 43f * unit, center.y - 25f * unit), Size(30f * unit, 34f * unit), style = Stroke(5f * unit))
                drawArc(NovvinGold, -95f, 190f, false, Offset(center.x + 13f * unit, center.y - 25f * unit), Size(30f * unit, 34f * unit), style = Stroke(5f * unit))
            }
            "builtin-fireworks" -> {
                repeat(3) { burst ->
                    val origin = center + when (burst) {
                        0 -> Offset(-22f * unit, 8f * unit)
                        1 -> Offset(20f * unit, -15f * unit)
                        else -> Offset(10f * unit, 25f * unit)
                    }
                    repeat(10) { ray ->
                        val angle = ray * (PI * 2 / 10)
                        val end = origin + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (20f * unit)
                        drawLine(listOf(NovvinPink, NovvinGold, NovvinCyan)[burst], origin, end, 3f * unit, StrokeCap.Round)
                    }
                }
            }
            "builtin-star" -> drawPath(starPath(center, 39f * unit, 18f * unit, 8), Brush.linearGradient(listOf(NovvinCyan, NovvinPurple, NovvinPink)))
            "builtin-orbit" -> {
                drawCircle(Brush.linearGradient(listOf(NovvinCyan, NovvinPurple)), 24f * unit, center)
                rotate(-18f, center) {
                    drawOval(Color.White.copy(alpha = 0.75f), Offset(center.x - 44f * unit, center.y - 13f * unit), Size(88f * unit, 26f * unit), style = Stroke(6f * unit))
                }
                drawCircle(Color.White.copy(alpha = 0.7f), 5f * unit, center - Offset(8f * unit, 8f * unit))
            }
            "builtin-comet" -> {
                repeat(4) { index ->
                    drawLine(
                        color = listOf(NovvinPurple, NovvinCyan, NovvinPink, NovvinGold)[index],
                        start = Offset(8f * unit, (67f + index * 6f) * unit),
                        end = center + Offset(9f * unit, (index - 2) * 3f * unit),
                        strokeWidth = (8f - index) * unit,
                        cap = StrokeCap.Round
                    )
                }
                drawCircle(Brush.radialGradient(listOf(Color.White, NovvinCyan, NovvinPurple)), 25f * unit, center + Offset(13f * unit, -9f * unit))
            }
            "builtin-crown" -> {
                val crown = Path().apply {
                    moveTo(center.x - 42f * unit, center.y + 27f * unit)
                    lineTo(center.x - 36f * unit, center.y - 23f * unit)
                    lineTo(center.x - 12f * unit, center.y + 1f * unit)
                    lineTo(center.x, center.y - 36f * unit)
                    lineTo(center.x + 15f * unit, center.y + 1f * unit)
                    lineTo(center.x + 39f * unit, center.y - 23f * unit)
                    lineTo(center.x + 42f * unit, center.y + 27f * unit)
                    close()
                }
                drawPath(crown, Brush.verticalGradient(listOf(Color.White, NovvinGold, Color(0xFFFF8F3D))))
                drawRoundRect(NovvinPurple, Offset(center.x - 42f * unit, center.y + 23f * unit), Size(84f * unit, 14f * unit), androidx.compose.ui.geometry.CornerRadius(7f * unit))
                listOf(-25f, 0f, 25f).forEach { x -> drawCircle(NovvinPink, 5f * unit, center + Offset(x * unit, 28f * unit)) }
            }
            else -> {
                val heart = Path().apply {
                    moveTo(center.x, center.y + 35f * unit)
                    cubicTo(center.x - 52f * unit, center.y + 7f * unit, center.x - 43f * unit, center.y - 31f * unit, center.x - 18f * unit, center.y - 31f * unit)
                    cubicTo(center.x - 7f * unit, center.y - 31f * unit, center.x - 1f * unit, center.y - 23f * unit, center.x, center.y - 15f * unit)
                    cubicTo(center.x + 2f * unit, center.y - 23f * unit, center.x + 8f * unit, center.y - 31f * unit, center.x + 19f * unit, center.y - 31f * unit)
                    cubicTo(center.x + 44f * unit, center.y - 31f * unit, center.x + 51f * unit, center.y + 7f * unit, center.x, center.y + 35f * unit)
                    close()
                }
                drawPath(heart, Brush.linearGradient(listOf(NovvinPink, NovvinPurple)))
                drawLine(Color.White.copy(alpha = 0.75f), center - Offset(18f * unit, 5f * unit), center - Offset(6f * unit, 14f * unit), 5f * unit, StrokeCap.Round)
            }
        }
    }
}

private fun starPath(center: Offset, outer: Float, inner: Float, points: Int): Path = Path().apply {
    repeat(points * 2) { index ->
        val angle = -PI / 2 + index * PI / points
        val radius = if (index % 2 == 0) outer else inner
        val point = center + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius)
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}
