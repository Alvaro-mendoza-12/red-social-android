package conectate.com.presentation.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import conectate.com.R
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.visualKey
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val NovvinPink = Color(0xFFFF4F87)
private val NovvinPurple = Color(0xFF8F67FF)
private val NovvinCyan = Color(0xFF35D7FF)
private val NovvinGold = Color(0xFFFFC857)
private val NovvinOrange = Color(0xFFFF7849)

@Composable
fun GiftArtwork(gift: VirtualGift, modifier: Modifier = Modifier) {
    GiftArtworkByKey(gift.visualKey(), modifier)
}

@Composable
private fun GiftArtworkByKey(visualKey: String, modifier: Modifier) {
    val generatedArtwork = when (visualKey) {
        "starlight-seed" -> R.drawable.gift_starlight_seed
        "aurora-drop" -> R.drawable.gift_aurora_drop
        "prism-spark" -> R.drawable.gift_prism_spark
        "luminous-butterfly" -> R.drawable.gift_luminous_butterfly
        "comet-flower" -> R.drawable.gift_comet_flower
        "crystal-moon" -> R.drawable.gift_crystal_moon
        "solar-hummingbird" -> R.drawable.gift_solar_hummingbird
        "nebula-jellyfish" -> R.drawable.gift_nebula_jellyfish
        "boreal-fox" -> R.drawable.gift_boreal_fox
        "cosmic-whale" -> R.drawable.gift_cosmic_whale
        "star-garden" -> R.drawable.gift_star_garden
        "crystal-orchid" -> R.drawable.gift_crystal_orchid
        "prism-ship" -> R.drawable.gift_prism_ship
        "forest-guardian" -> R.drawable.gift_forest_guardian
        "aurora-phoenix" -> R.drawable.gift_aurora_phoenix
        "star-train" -> R.drawable.gift_star_train
        "celestial-dragon" -> R.drawable.gift_celestial_dragon
        "floating-island" -> R.drawable.gift_floating_island
        "celestial-world" -> R.drawable.gift_celestial_world
        "light-opera" -> R.drawable.gift_light_opera
        "aurora-palace" -> R.drawable.gift_aurora_palace
        "time-kingdom" -> R.drawable.gift_time_kingdom
        "galactic-ascent" -> R.drawable.gift_galactic_ascent
        "aurora-genesis" -> R.drawable.gift_aurora_genesis
        else -> null
    }
    if (generatedArtwork != null) {
        Image(
            painter = painterResource(generatedArtwork),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
        return
    }
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val unit = size.minDimension / 100f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.25f), Color.Transparent),
                center = center,
                radius = 49f * unit
            ),
            radius = 49f * unit,
            center = center
        )

        when (visualKey) {
            "aurora" -> drawAurora(center, unit)
            "prism" -> drawPrismCup(center, unit)
            "fireworks" -> drawFireworks(center, unit)
            "nova" -> drawPath(
                starPath(center, 39f * unit, 18f * unit, 8),
                Brush.linearGradient(listOf(NovvinCyan, NovvinPurple, NovvinPink))
            )
            "orbit" -> {
                drawCircle(Brush.linearGradient(listOf(NovvinCyan, NovvinPurple)), 24f * unit, center)
                rotate(-18f, center) {
                    drawOval(
                        Color.White.copy(alpha = 0.82f),
                        Offset(center.x - 44f * unit, center.y - 13f * unit),
                        Size(88f * unit, 26f * unit),
                        style = Stroke(6f * unit)
                    )
                }
                drawCircle(Color.White.copy(alpha = 0.78f), 5f * unit, center - Offset(8f * unit, 8f * unit))
            }
            "comet" -> {
                repeat(4) { index ->
                    drawLine(
                        color = listOf(NovvinPurple, NovvinCyan, NovvinPink, NovvinGold)[index],
                        start = Offset(8f * unit, (67f + index * 6f) * unit),
                        end = center + Offset(9f * unit, (index - 2) * 3f * unit),
                        strokeWidth = (8f - index) * unit,
                        cap = StrokeCap.Round
                    )
                }
                drawCircle(
                    Brush.radialGradient(listOf(Color.White, NovvinCyan, NovvinPurple)),
                    25f * unit,
                    center + Offset(13f * unit, -9f * unit)
                )
            }
            "crown" -> drawCrown(center, unit)
            "crystal" -> {
                val crystal = Path().apply {
                    moveTo(center.x, center.y - 43f * unit)
                    lineTo(center.x + 34f * unit, center.y - 12f * unit)
                    lineTo(center.x + 22f * unit, center.y + 37f * unit)
                    lineTo(center.x, center.y + 46f * unit)
                    lineTo(center.x - 22f * unit, center.y + 37f * unit)
                    lineTo(center.x - 34f * unit, center.y - 12f * unit)
                    close()
                }
                drawPath(crystal, Brush.linearGradient(listOf(Color.White, NovvinCyan, NovvinPurple)))
                drawLine(Color.White.copy(alpha = 0.8f), center - Offset(0f, 40f * unit), center, 3f * unit)
                drawLine(Color.White.copy(alpha = 0.55f), center, center + Offset(22f * unit, 37f * unit), 3f * unit)
            }
            "rocket" -> drawRocket(center, unit)
            "rhythm" -> {
                drawLine(NovvinPurple, center + Offset(-5f * unit, -31f * unit), center + Offset(-5f * unit, 24f * unit), 8f * unit, StrokeCap.Round)
                drawLine(NovvinPurple, center + Offset(-3f * unit, -29f * unit), center + Offset(31f * unit, -17f * unit), 7f * unit, StrokeCap.Round)
                drawLine(NovvinPink, center + Offset(30f * unit, -18f * unit), center + Offset(30f * unit, 13f * unit), 7f * unit, StrokeCap.Round)
                drawOval(NovvinCyan, center + Offset(-31f * unit, 15f * unit), Size(31f * unit, 21f * unit))
                drawOval(NovvinPink, center + Offset(8f * unit, 6f * unit), Size(31f * unit, 21f * unit))
            }
            "rainbow" -> {
                val colors = listOf(NovvinPink, NovvinOrange, NovvinGold, NovvinCyan, NovvinPurple)
                colors.forEachIndexed { index, color ->
                    val inset = (10f + index * 7f) * unit
                    drawArc(
                        color = color,
                        startAngle = 190f,
                        sweepAngle = 160f,
                        useCenter = false,
                        topLeft = Offset(inset, 24f * unit + inset / 3f),
                        size = Size(size.width - inset * 2f, size.height - 28f * unit - inset),
                        style = Stroke(6f * unit, cap = StrokeCap.Round)
                    )
                }
            }
            "wave" -> {
                repeat(3) { index ->
                    val y = center.y + (index - 1) * 15f * unit
                    val wave = Path().apply {
                        moveTo(7f * unit, y)
                        cubicTo(24f * unit, y - 17f * unit, 36f * unit, y + 17f * unit, 52f * unit, y)
                        cubicTo(67f * unit, y - 17f * unit, 78f * unit, y + 17f * unit, 94f * unit, y)
                    }
                    drawPath(
                        wave,
                        listOf(NovvinCyan, NovvinPurple, NovvinPink)[index],
                        style = Stroke((7f - index) * unit, cap = StrokeCap.Round)
                    )
                }
                drawCircle(Color.White, 6f * unit, center + Offset(20f * unit, -27f * unit))
            }
            "butterfly" -> {
                rotate(-24f, center) {
                    drawOval(Brush.linearGradient(listOf(NovvinPink, NovvinPurple)), center + Offset(-43f * unit, -31f * unit), Size(37f * unit, 47f * unit))
                    drawOval(Brush.linearGradient(listOf(NovvinCyan, NovvinPurple)), center + Offset(-39f * unit, 8f * unit), Size(32f * unit, 31f * unit))
                }
                rotate(24f, center) {
                    drawOval(Brush.linearGradient(listOf(NovvinCyan, NovvinPurple)), center + Offset(6f * unit, -31f * unit), Size(37f * unit, 47f * unit))
                    drawOval(Brush.linearGradient(listOf(NovvinPink, NovvinPurple)), center + Offset(7f * unit, 8f * unit), Size(32f * unit, 31f * unit))
                }
                drawLine(NovvinGold, center + Offset(0f, -23f * unit), center + Offset(0f, 31f * unit), 7f * unit, StrokeCap.Round)
                drawCircle(NovvinGold, 6f * unit, center + Offset(0f, -27f * unit))
            }
            "portal" -> {
                listOf(39f to NovvinPurple, 29f to NovvinCyan, 19f to NovvinPink).forEach { (radius, color) ->
                    drawCircle(color, radius * unit, center, style = Stroke(7f * unit))
                }
                drawCircle(Brush.radialGradient(listOf(Color.White, NovvinGold, Color.Transparent)), 12f * unit, center)
                repeat(6) { index ->
                    val angle = index * PI / 3
                    drawCircle(NovvinGold, 3f * unit, center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * 46f * unit)
                }
            }
            "phoenix" -> drawPhoenix(center, unit)
            else -> drawPulse(center, unit)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPulse(center: Offset, unit: Float) {
    val heart = Path().apply {
        moveTo(center.x, center.y + 35f * unit)
        cubicTo(center.x - 52f * unit, center.y + 7f * unit, center.x - 43f * unit, center.y - 31f * unit, center.x - 18f * unit, center.y - 31f * unit)
        cubicTo(center.x - 7f * unit, center.y - 31f * unit, center.x - 1f * unit, center.y - 23f * unit, center.x, center.y - 15f * unit)
        cubicTo(center.x + 2f * unit, center.y - 23f * unit, center.x + 8f * unit, center.y - 31f * unit, center.x + 19f * unit, center.y - 31f * unit)
        cubicTo(center.x + 44f * unit, center.y - 31f * unit, center.x + 51f * unit, center.y + 7f * unit, center.x, center.y + 35f * unit)
        close()
    }
    drawPath(heart, Brush.linearGradient(listOf(NovvinPink, NovvinPurple)))
    drawLine(Color.White.copy(alpha = 0.78f), center - Offset(18f * unit, 5f * unit), center - Offset(6f * unit, 14f * unit), 5f * unit, StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAurora(center: Offset, unit: Float) {
    repeat(8) { index ->
        rotate(index * 45f, center) {
            drawOval(Brush.linearGradient(listOf(NovvinPink, NovvinPurple)), Offset(center.x - 10f * unit, center.y - 39f * unit), Size(20f * unit, 36f * unit))
        }
    }
    drawCircle(NovvinGold, 12f * unit, center)
    drawCircle(Color.White.copy(alpha = 0.6f), 5f * unit, center - Offset(3f * unit, 3f * unit))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPrismCup(center: Offset, unit: Float) {
    val cup = Path().apply {
        moveTo(center.x - 27f * unit, center.y - 30f * unit)
        lineTo(center.x + 27f * unit, center.y - 30f * unit)
        cubicTo(center.x + 24f * unit, center.y + 2f * unit, center.x + 14f * unit, center.y + 15f * unit, center.x, center.y + 18f * unit)
        cubicTo(center.x - 14f * unit, center.y + 15f * unit, center.x - 24f * unit, center.y + 2f * unit, center.x - 27f * unit, center.y - 30f * unit)
        close()
    }
    drawPath(cup, Brush.linearGradient(listOf(NovvinGold, NovvinOrange)))
    drawLine(NovvinGold, center + Offset(0f, 16f * unit), center + Offset(0f, 34f * unit), 8f * unit, StrokeCap.Round)
    drawRoundRect(NovvinGold, Offset(center.x - 22f * unit, center.y + 31f * unit), Size(44f * unit, 9f * unit), CornerRadius(5f * unit))
    drawArc(NovvinGold, 85f, 190f, false, Offset(center.x - 43f * unit, center.y - 25f * unit), Size(30f * unit, 34f * unit), style = Stroke(5f * unit))
    drawArc(NovvinGold, -95f, 190f, false, Offset(center.x + 13f * unit, center.y - 25f * unit), Size(30f * unit, 34f * unit), style = Stroke(5f * unit))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFireworks(center: Offset, unit: Float) {
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrown(center: Offset, unit: Float) {
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
    drawPath(crown, Brush.verticalGradient(listOf(Color.White, NovvinGold, NovvinOrange)))
    drawRoundRect(NovvinPurple, Offset(center.x - 42f * unit, center.y + 23f * unit), Size(84f * unit, 14f * unit), CornerRadius(7f * unit))
    listOf(-25f, 0f, 25f).forEach { x -> drawCircle(NovvinPink, 5f * unit, center + Offset(x * unit, 28f * unit)) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRocket(center: Offset, unit: Float) {
    val body = Path().apply {
        moveTo(center.x, center.y - 44f * unit)
        cubicTo(center.x + 29f * unit, center.y - 25f * unit, center.x + 26f * unit, center.y + 18f * unit, center.x, center.y + 31f * unit)
        cubicTo(center.x - 26f * unit, center.y + 18f * unit, center.x - 29f * unit, center.y - 25f * unit, center.x, center.y - 44f * unit)
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(Color.White, NovvinCyan, NovvinPurple)))
    drawCircle(Color(0xFF18223D), 9f * unit, center - Offset(0f, 13f * unit))
    drawCircle(Color.White.copy(alpha = 0.75f), 4f * unit, center - Offset(3f * unit, 16f * unit))
    drawPath(Path().apply { moveTo(center.x - 17f * unit, center.y + 17f * unit); lineTo(center.x - 39f * unit, center.y + 36f * unit); lineTo(center.x - 10f * unit, center.y + 31f * unit); close() }, NovvinPink)
    drawPath(Path().apply { moveTo(center.x + 17f * unit, center.y + 17f * unit); lineTo(center.x + 39f * unit, center.y + 36f * unit); lineTo(center.x + 10f * unit, center.y + 31f * unit); close() }, NovvinPink)
    drawPath(Path().apply { moveTo(center.x - 9f * unit, center.y + 29f * unit); lineTo(center.x, center.y + 48f * unit); lineTo(center.x + 9f * unit, center.y + 29f * unit); close() }, Brush.verticalGradient(listOf(NovvinGold, NovvinOrange, NovvinPink)))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPhoenix(center: Offset, unit: Float) {
    val leftWing = Path().apply {
        moveTo(center.x, center.y - 17f * unit)
        cubicTo(center.x - 22f * unit, center.y - 40f * unit, center.x - 44f * unit, center.y - 37f * unit, center.x - 47f * unit, center.y - 10f * unit)
        cubicTo(center.x - 31f * unit, center.y - 18f * unit, center.x - 20f * unit, center.y + 4f * unit, center.x, center.y + 14f * unit)
        close()
    }
    val rightWing = Path().apply {
        moveTo(center.x, center.y - 17f * unit)
        cubicTo(center.x + 22f * unit, center.y - 40f * unit, center.x + 44f * unit, center.y - 37f * unit, center.x + 47f * unit, center.y - 10f * unit)
        cubicTo(center.x + 31f * unit, center.y - 18f * unit, center.x + 20f * unit, center.y + 4f * unit, center.x, center.y + 14f * unit)
        close()
    }
    drawPath(leftWing, Brush.linearGradient(listOf(NovvinPink, NovvinOrange, NovvinGold)))
    drawPath(rightWing, Brush.linearGradient(listOf(NovvinGold, NovvinOrange, NovvinPink)))
    drawOval(NovvinGold, center + Offset(-8f * unit, -21f * unit), Size(16f * unit, 45f * unit))
    repeat(3) { index ->
        val x = (index - 1) * 12f * unit
        drawPath(Path().apply {
            moveTo(center.x + x - 6f * unit, center.y + 18f * unit)
            quadraticTo(center.x + x, center.y + 47f * unit, center.x + x + 6f * unit, center.y + 22f * unit)
            close()
        }, listOf(NovvinPink, NovvinGold, NovvinPurple)[index])
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
