package conectate.com.presentation.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
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
        val maxWidthPx = with(density) { maxWidth.toPx() }

        reactions.forEach { reaction ->
            FloatingReaction(
                reaction = reaction,
                maxWidthPx = maxWidthPx
            )
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.FloatingReaction(
    reaction: LiveReaction,
    maxWidthPx: Float
) {
    val density = LocalDensity.current
    val seed = abs(reaction.id.hashCode())

    // Keep reactions within ~75% of the width (bottom-left area like TikTok)
    val maxX = (maxWidthPx * 0.75f).toInt().coerceAtLeast(1)
    val xPx = (seed % maxX).toFloat()
    val xDp = with(density) { xPx.toDp() }

    val offsetY = remember(reaction.id) { Animatable(0f) } // dp
    val alpha = remember(reaction.id) { Animatable(1f) }

    LaunchedEffect(reaction.id) {
        // Up + fade out
        offsetY.animateTo(
            targetValue = -220f,
            animationSpec = tween(durationMillis = 2600, easing = LinearOutSlowInEasing)
        )
    }
    LaunchedEffect(reaction.id) {
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 2600)
        )
    }

    Text(
        text = reaction.emoji,
        modifier = Modifier
            .offset(x = xDp, y = offsetY.value.dp)
            .offset(y = (-8).dp)
            .align(Alignment.BottomStart),
        style = TextStyle(
            fontSize = 22.sp
        ),
        maxLines = 1,
        color = androidx.compose.ui.graphics.Color.White.copy(alpha = alpha.value)
    )
}
