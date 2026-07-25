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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import coil.compose.SubcomposeAsyncImage
import androidx.compose.ui.layout.ContentScale

@Composable
fun ChatBackgroundLayer(
    backgroundType: String,
    backgroundUri: String?,
    modifier: Modifier = Modifier
) {
    val base = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(base)
    ) {
        when (backgroundType.trim().lowercase()) {
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

            "pattern_doodles" -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val lineColor = Color(0xFF7F93A0).copy(alpha = 0.20f)
                    val dotColor = Color(0xFF5E7381).copy(alpha = 0.16f)
                    val spacing = size.minDimension / 7.5f
                    val radius = size.minDimension / 120f
                    val stroke = size.minDimension / 95f
                    val dash = PathEffect.dashPathEffect(floatArrayOf(stroke * 2f, stroke * 3f))

                    var row = 0
                    var y = spacing / 2f
                    while (y < size.height + spacing) {
                        var x = if (row % 2 == 0) spacing / 2f else spacing
                        while (x < size.width + spacing) {
                            drawCircle(dotColor, radius = radius, center = Offset(x, y))
                            drawArc(
                                color = lineColor,
                                startAngle = 0f,
                                sweepAngle = 180f,
                                useCenter = false,
                                topLeft = Offset(x - spacing / 3f, y - spacing / 3f),
                                size = Size(spacing / 1.5f, spacing / 1.5f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = stroke,
                                    pathEffect = dash
                                )
                            )
                            x += spacing
                        }
                        y += spacing * 0.85f
                        row++
                    }
                }
            }

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

