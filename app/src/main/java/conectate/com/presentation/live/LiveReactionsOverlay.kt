package conectate.com.presentation.live

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import conectate.com.data.model.LiveReaction
import kotlin.math.abs

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

        reactions.takeLast(12).forEach { reaction ->
            FloatingReaction(
                reaction = reaction,
                maxWidthPx = maxWidthPx,
                animationsEnabled = animationsEnabled
            )
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.FloatingReaction(
    reaction: LiveReaction,
    maxWidthPx: Float,
    animationsEnabled: Boolean
) {
    val density = LocalDensity.current
    val seed = abs(reaction.id.hashCode())

    // Keep reactions within ~75% of the width (bottom-left area like TikTok)
    val maxX = (maxWidthPx * 0.75f).toInt().coerceAtLeast(1)
    val xPx = (seed % maxX).toFloat()
    val xDp = with(density) { xPx.toDp() }

    val offsetY = remember(reaction.id) { Animatable(0f) }
    val alpha = remember(reaction.id) { Animatable(1f) }

    LaunchedEffect(reaction.id) {
        if (animationsEnabled) {
            offsetY.animateTo(
                targetValue = -160f,
                animationSpec = tween(durationMillis = 1100, easing = LinearOutSlowInEasing)
            )
        }
    }
    LaunchedEffect(reaction.id) {
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 1100)
        )
    }

    Icon(
        imageVector = Icons.Default.Favorite,
        contentDescription = null,
        modifier = Modifier
            .offset(x = xDp, y = offsetY.value.dp)
            .offset(y = (-8).dp)
            .size(28.dp)
            .align(Alignment.BottomStart),
        tint = Color(0xFFFF4F87).copy(alpha = alpha.value)
    )
}
