package conectate.com.presentation.live

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import conectate.com.data.model.LiveReaction
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

@Composable
fun LiveReactionsOverlay(
    reactions: List<LiveReaction>,
    modifier: Modifier = Modifier
) {
    if (reactions.isEmpty()) return

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val context = LocalContext.current
        val animationsEnabled = remember {
            runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }.getOrDefault(true)
        }
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxRiseDp = (maxHeight.value - 88f).coerceIn(260f, 900f)

        reactions.takeLast(20).forEach { reaction ->
            FloatingReaction(
                reaction = reaction,
                maxWidthPx = maxWidthPx,
                maxRiseDp = maxRiseDp,
                animationsEnabled = animationsEnabled
            )
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.FloatingReaction(
    reaction: LiveReaction,
    maxWidthPx: Float,
    maxRiseDp: Float,
    animationsEnabled: Boolean
) {
    val density = LocalDensity.current
    val seed = abs(reaction.id.hashCode())

    // Keep hearts in the dedicated right-side lane from the reference layout;
    // chat, PiP and the composer remain readable on the left/center.
    val laneStart = maxWidthPx * 0.72f
    val laneWidth = (maxWidthPx * 0.22f).coerceAtLeast(1f)
    val xPx = laneStart + (seed % laneWidth.toInt().coerceAtLeast(1)).toFloat()
    val xDp = with(density) { xPx.toDp() }

    val progress = remember(reaction.id) { Animatable(0f) }

    LaunchedEffect(reaction.id) {
        if (animationsEnabled) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 2_850, easing = LinearEasing)
            )
        }
    }
    val motion = reactionMotion(
        progress = if (animationsEnabled) progress.value else 0.28f,
        seed = seed,
        maxRiseDp = maxRiseDp,
    )

    Icon(
        imageVector = Icons.Default.Favorite,
        contentDescription = null,
        modifier = Modifier
            .offset(x = xDp + motion.lateralDp.dp, y = motion.riseDp.dp)
            .offset(y = (-8).dp)
            .size(28.dp)
            .graphicsLayer {
                alpha = motion.alpha
                scaleX = motion.scale
                scaleY = motion.scale
                rotationZ = motion.rotationDegrees
            }
            .align(Alignment.BottomStart),
        tint = Color(0xFFFF4F87)
    )
}

internal data class LiveReactionMotion(
    val riseDp: Float,
    val lateralDp: Float,
    val alpha: Float,
    val scale: Float,
    val rotationDegrees: Float
)

/** Stable, testable trajectory: visible first, then a soft curved fade. */
internal fun reactionMotion(progress: Float, seed: Int, maxRiseDp: Float = 220f): LiveReactionMotion {
    val p = progress.coerceIn(0f, 1f)
    val easedRise = 1f - (1f - p) * (1f - p)
    val phase = (seed % 360) * (PI.toFloat() / 180f)
    val direction = if (seed % 2 == 0) 1f else -1f
    val lateral = sin(p * PI.toFloat() * 1.6f + phase) * 14f * direction
    val fade = if (p <= 0.72f) 1f else (1f - (p - 0.72f) / 0.28f).coerceIn(0f, 1f)
    val scale = when {
        p < 0.12f -> 0.72f + (p / 0.12f) * 0.46f
        else -> 1.18f - ((p - 0.12f) / 0.88f) * 0.28f
    }
    return LiveReactionMotion(
        riseDp = -maxRiseDp.coerceAtLeast(220f) * easedRise,
        lateralDp = lateral,
        alpha = fade,
        scale = scale,
        rotationDegrees = lateral * 0.35f
    )
}
