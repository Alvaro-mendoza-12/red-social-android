package conectate.com.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.SharedPreferences
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import conectate.com.presentation.theme.ProfileThemeColors
import conectate.com.presentation.theme.profileThemeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun shouldAnimateProfileAmbient(effectId: String, reduceMotion: Boolean, resumed: Boolean): Boolean =
    !reduceMotion && resumed && effectId.trim().lowercase(java.util.Locale.ROOT) in
        setOf("sparkles", "constellation", "confetti", "waves", "orbit", "rain", "equalizer")

@Composable
fun ProfileAmbientBackground(
    themeId: String,
    effectId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = profileThemeColors(themeId, dark)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember(context) { context.getSharedPreferences("app_settings_preferences", android.content.Context.MODE_PRIVATE) }
    val reduceMotion = remember(prefs) { mutableStateOf(prefs.getBoolean("reduce_motion_enabled", false)) }
    val resumed = remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(prefs, lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == "reduce_motion_enabled") reduceMotion.value = shared.getBoolean(key, false)
        }
        val observer = LifecycleEventObserver { _, _ -> resumed.value = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); lifecycle.removeObserver(observer) }
    }
    val phase = if (shouldAnimateProfileAmbient(effectId, reduceMotion.value || !android.animation.ValueAnimator.areAnimatorsEnabled(), resumed.value)) {
        val transition = rememberInfiniteTransition(label = "profile-ambient")
        transition.animateFloat(0f, 1f,
            infiniteRepeatable(tween(durationMillis = 6_000, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "profile-ambient-phase")
    } else remember { mutableStateOf(0f) }

    Box(modifier = modifier.background(colors.background)) {
        ProfilePattern(
            effectId = effectId,
            colors = colors,
            phase = phase,
            modifier = Modifier.fillMaxSize()
        )
        content()
    }
}

@Composable
private fun ProfilePattern(
    effectId: String,
    colors: ProfileThemeColors,
    phase: State<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        // Read animation state during drawing, without recomposing the profile and its content each frame.
        val phase = phase.value
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        drawLine(
            color = colors.secondary.copy(alpha = 0.15f),
            start = Offset(-width * 0.05f, height * 0.78f),
            end = Offset(width * 0.48f, height * 0.18f),
            strokeWidth = 3f
        )
        drawLine(
            color = colors.accent.copy(alpha = 0.12f),
            start = Offset(width * 0.56f, height * 1.05f),
            end = Offset(width * 1.05f, height * 0.35f),
            strokeWidth = 5f
        )

        when (effectId.trim().lowercase()) {
            "sparkles" -> {
                val points = listOf(
                    0.10f to 0.24f,
                    0.24f to 0.68f,
                    0.44f to 0.35f,
                    0.66f to 0.73f,
                    0.82f to 0.22f,
                    0.92f to 0.54f
                )
                points.forEachIndexed { index, (x, y) ->
                    val pulse = 0.35f + 0.65f * ((sin((phase + index * 0.17f) * 2f * PI).toFloat() + 1f) / 2f)
                    drawSparkle(
                        center = Offset(width * x, height * y),
                        radius = 5f + 5f * pulse,
                        color = colors.foreground.copy(alpha = 0.28f + 0.42f * pulse)
                    )
                }
            }

            "constellation" -> {
                val points = listOf(
                    Offset(width * 0.09f, height * 0.62f),
                    Offset(width * 0.25f, height * 0.34f),
                    Offset(width * 0.46f, height * 0.55f),
                    Offset(width * 0.67f, height * 0.27f),
                    Offset(width * 0.88f, height * 0.46f)
                )
                points.zipWithNext().forEach { (start, end) ->
                    drawLine(
                        color = colors.foreground.copy(alpha = 0.22f),
                        start = start,
                        end = end,
                        strokeWidth = 2f
                    )
                }
                points.forEachIndexed { index, point ->
                    drawSparkle(
                        center = point,
                        radius = 5f + 2f * sin((phase + index * 0.2f) * 2f * PI).toFloat(),
                        color = colors.foreground.copy(alpha = 0.64f)
                    )
                }
            }

            "confetti" -> {
                val pieces = listOf(
                    Triple(0.08f, 0.18f, 0.12f),
                    Triple(0.19f, 0.62f, -0.10f),
                    Triple(0.34f, 0.30f, 0.16f),
                    Triple(0.52f, 0.76f, -0.14f),
                    Triple(0.69f, 0.20f, 0.10f),
                    Triple(0.83f, 0.58f, -0.12f),
                    Triple(0.94f, 0.34f, 0.14f)
                )
                pieces.forEachIndexed { index, (x, initialY, slant) ->
                    val y = (initialY + phase) % 1f
                    val start = Offset(width * x, height * y)
                    drawLine(
                        color = if (index % 2 == 0) {
                            colors.accent.copy(alpha = 0.72f)
                        } else {
                            colors.secondary.copy(alpha = 0.72f)
                        },
                        start = start,
                        end = Offset(start.x + width * slant * 0.08f, start.y + 12f),
                        strokeWidth = 5f
                    )
                }
            }

            "waves" -> {
                repeat(4) { wave ->
                    val yBase = height * (0.22f + wave * 0.18f)
                    var previous = Offset(0f, yBase)
                    repeat(32) { step ->
                        val x = width * (step + 1) / 32f
                        val y = yBase + sin((step / 5f + phase * 2f * PI + wave).toFloat()) * height * 0.035f
                        val next = Offset(x, y)
                        drawLine(
                            color = if (wave % 2 == 0) colors.accent.copy(alpha = 0.28f) else colors.secondary.copy(alpha = 0.24f),
                            start = previous,
                            end = next,
                            strokeWidth = 3f
                        )
                        previous = next
                    }
                }
            }

            "orbit" -> {
                val center = Offset(width * 0.5f, height * 0.5f)
                listOf(0.16f, 0.27f, 0.39f).forEachIndexed { index, radiusFactor ->
                    val radius = width * radiusFactor
                    drawCircle(
                        color = colors.foreground.copy(alpha = 0.16f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2f)
                    )
                    val angle = (phase * 2f * PI + index * 2.1f).toFloat()
                    drawCircle(
                        color = if (index == 1) colors.secondary else colors.accent,
                        radius = 5f + index,
                        center = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
                    )
                }
            }

            "rain" -> {
                repeat(14) { index ->
                    val x = width * ((index * 0.073f + 0.04f) % 1f)
                    val y = height * ((index * 0.19f + phase * 2f) % 1f)
                    drawLine(
                        color = if (index % 3 == 0) colors.secondary.copy(alpha = 0.5f) else colors.foreground.copy(alpha = 0.3f),
                        start = Offset(x, y),
                        end = Offset(x - 7f, y + 18f),
                        strokeWidth = 3f
                    )
                }
            }

            "equalizer" -> {
                repeat(12) { index ->
                    val pulse = (sin((phase * 2f * PI + index * 0.72f)).toFloat() + 1f) / 2f
                    val barHeight = height * (0.10f + pulse * 0.28f)
                    val barWidth = width / 28f
                    drawRect(
                        color = if (index % 2 == 0) colors.accent.copy(alpha = 0.48f) else colors.secondary.copy(alpha = 0.48f),
                        topLeft = Offset(width * (0.08f + index * 0.071f), height * 0.82f - barHeight),
                        size = Size(barWidth, barHeight)
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparkle(
    center: Offset,
    radius: Float,
    color: Color
) {
    drawLine(
        color = color,
        start = Offset(center.x - radius, center.y),
        end = Offset(center.x + radius, center.y),
        strokeWidth = 2.5f
    )
    drawLine(
        color = color,
        start = Offset(center.x, center.y - radius),
        end = Offset(center.x, center.y + radius),
        strokeWidth = 2.5f
    )
}

@Composable
fun ProfileWelcomeBanner(
    message: String,
    themeId: String,
    modifier: Modifier = Modifier
) {
    if (message.isBlank()) return
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = profileThemeColors(themeId, dark)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.messageBackground,
        contentColor = colors.messageForeground,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = colors.accent
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.messageForeground,
                maxLines = 3
            )
        }
    }
}
