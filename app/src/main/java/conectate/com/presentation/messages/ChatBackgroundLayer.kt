package conectate.com.presentation.messages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import coil.compose.SubcomposeAsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import conectate.com.presentation.theme.LocalChatBackgroundKey

@Composable
fun ChatBackgroundLayer(
    backgroundType: String,
    backgroundUri: String?,
    modifier: Modifier = Modifier
) {
    val base = MaterialTheme.colorScheme.surface
    val globalBackground = LocalChatBackgroundKey.current
    val effectiveType = backgroundType.trim().lowercase().let { type ->
        if (type.isBlank() || type == "default") globalBackground.trim().lowercase() else type
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer()
            .background(base)
    ) {
        when (effectiveType) {
            "deep_black" -> Box(Modifier.fillMaxSize().background(Color(0xFF08090B)))

            "soft_noise" -> {
                Box(Modifier.fillMaxSize().background(Color(0xFF17191D)))
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val spacing = (size.minDimension / 28f).coerceAtLeast(14f)
                    val dot = (size.minDimension / 360f).coerceAtLeast(1f)
                    var row = 0
                    var y = spacing / 2f
                    while (y < size.height) {
                        var x = if (row % 2 == 0) spacing / 2f else spacing
                        while (x < size.width) {
                            drawCircle(
                                color = Color.White.copy(alpha = if ((row + x.toInt()) % 3 == 0) 0.045f else 0.025f),
                                radius = dot,
                                center = Offset(x, y),
                            )
                            x += spacing
                        }
                        y += spacing
                        row += 1
                    }
                }
            }

            "midnight" -> PresetGradient(
                colors = listOf(Color(0xFF07111F), Color(0xFF102A43), Color(0xFF080D17))
            )

            "minimal" -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            )

            "custom" -> {
                if (!backgroundUri.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = backgroundUri,
                        contentDescription = "Fondo de chat",
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.25f),
                        contentScale = ContentScale.Crop,
                        loading = {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    )
                }
            }

            "pattern_grid" -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = size.minDimension / 90f
                    val step = size.minDimension / 14f
                    val color = Color(0xFF8FA8B2).copy(alpha = 0.16f)
                    var x = 0f
                    while (x < size.width) {
                        drawLine(
                            color = color,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = stroke
                        )
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(
                            color = color,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = stroke
                        )
                        y += step
                    }
                }
            }

            "pattern_doodles" -> ChatDoodleWallpaper()

            "preset_ocean" -> PresetGradient(
                colors = listOf(Color(0xFF071B26), Color(0xFF0B3948), Color(0xFF10242D))
            )

            "preset_lavender" -> PresetGradient(
                colors = listOf(Color(0xFF21182F), Color(0xFF3B2A4D), Color(0xFF191521))
            )

            "preset_warm" -> PresetGradient(
                colors = listOf(Color(0xFF2B1814), Color(0xFF4A2920), Color(0xFF211410))
            )

            "preset_graphite" -> PresetGradient(
                colors = listOf(Color(0xFF101214), Color(0xFF25292E), Color(0xFF111214))
            )

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surface,
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun PresetGradient(colors: List<Color>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors))
    )
}


/** Original line art, repeated at a stable density on every screen size. */
@Composable
private fun ChatDoodleWallpaper() {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.085f)
    Canvas(Modifier.fillMaxSize()) {
        val tile = 132.dp.toPx()
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
        var row = 0
        var y = -tile / 4
        while (y < size.height) {
            var x = if (row % 2 == 0) -tile / 4 else tile / 4
            while (x < size.width) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x + tile * .12f, y + tile * .15f)
                    lineTo(x + tile * .42f, y + tile * .15f)
                    quadraticTo(x + tile * .49f, y + tile * .15f, x + tile * .49f, y + tile * .22f)
                    lineTo(x + tile * .49f, y + tile * .39f)
                    lineTo(x + tile * .28f, y + tile * .39f)
                    lineTo(x + tile * .18f, y + tile * .47f)
                    lineTo(x + tile * .18f, y + tile * .39f)
                    lineTo(x + tile * .12f, y + tile * .39f)
                    close()
                }
                drawPath(path, color, style = stroke)
                val star = androidx.compose.ui.graphics.Path().apply {
                    for (i in 0 until 10) {
                        val angle = Math.PI * i / 5 - Math.PI / 2
                        val radius = tile * if (i % 2 == 0) .13f else .058f
                        val px = x + tile * .77f + kotlin.math.cos(angle).toFloat() * radius
                        val py = y + tile * .3f + kotlin.math.sin(angle).toFloat() * radius
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                }
                drawPath(star, color, style = stroke)
                val center = Offset(x + tile * .53f, y + tile * .76f)
                drawCircle(color, tile * .14f, center, style = stroke)
                drawArc(color, 15f, 150f, false, Offset(center.x - tile * .08f, center.y - tile * .04f), Size(tile * .16f, tile * .12f), style = stroke)
                drawCircle(color, 1.dp.toPx(), Offset(center.x - tile * .05f, center.y - tile * .03f))
                drawCircle(color, 1.dp.toPx(), Offset(center.x + tile * .05f, center.y - tile * .03f))
                x += tile
            }
            y += tile
            row++
        }
    }
}
