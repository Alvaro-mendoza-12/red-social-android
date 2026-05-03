package conectate.com.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class FloatingEmojiReaction(
    val id: String,
    val emoji: String
)

@Composable
fun FloatingEmojiReactionOverlay(
    reactions: List<FloatingEmojiReaction>,
    modifier: Modifier = Modifier
) {
    if (reactions.isEmpty()) return

    BoxWithConstraints(modifier = modifier) {
        reactions.forEach { reaction ->
            key(reaction.id) {
                FloatingEmojiReactionBubble(reaction = reaction)
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.FloatingEmojiReactionBubble(
    reaction: FloatingEmojiReaction
) {
    val seed = abs(reaction.id.hashCode())
    val random = remember(reaction.id) { Random(seed) }
    val baseOffset = remember(reaction.id) { random.nextFloat() * 132f - 66f }
    val amplitude = remember(reaction.id) { 24f + random.nextFloat() * 42f }
    val verticalDistance = remember(reaction.id) { 320f + random.nextFloat() * 170f }
    val drift = remember(reaction.id) { random.nextFloat() * 54f - 27f }
    val phase = remember(reaction.id) { random.nextFloat() * PI.toFloat() }
    val rotationTarget = remember(reaction.id) { random.nextFloat() * 14f - 7f }

    val progress = remember(reaction.id) { Animatable(0f) }
    val alphaAnim = remember(reaction.id) { Animatable(0f) }
    val scaleAnim = remember(reaction.id) { Animatable(0.72f) }

    LaunchedEffect(reaction.id) {
        launch {
            alphaAnim.animateTo(1f, animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing))
        }
        launch {
            scaleAnim.animateTo(1.16f, animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing))
            scaleAnim.animateTo(1f, animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing))
        }
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 2900, easing = LinearOutSlowInEasing)
        )
    }

    LaunchedEffect(reaction.id) {
        delay(2100)
        alphaAnim.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 720, easing = FastOutLinearInEasing)
        )
    }

    val currentProgress = progress.value
    val xOffset = baseOffset +
        (sin(currentProgress * PI.toFloat() * 2f + phase) * amplitude).toFloat() +
        drift * currentProgress
    val yOffset = -(verticalDistance * currentProgress)
    val currentScale = scaleAnim.value * (1f + (0.08f * (1f - currentProgress)))
    val rotation = rotationTarget * currentProgress
    val bubbleColor = if (currentProgress < 0.55f) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.22f)
    } else {
        Color.White.copy(alpha = 0.16f)
    }

    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .graphicsLayer {
                translationX = xOffset
                translationY = yOffset
                alpha = alphaAnim.value
                scaleX = currentScale
                scaleY = currentScale
                rotationZ = rotation
            }
            .sizeIn(minWidth = 56.dp, minHeight = 56.dp),
        color = bubbleColor,
        shape = androidx.compose.foundation.shape.CircleShape,
        shadowElevation = 10.dp,
        tonalElevation = 0.dp
    ) {
        Text(
            text = reaction.emoji,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            style = TextStyle(fontSize = 31.sp)
        )
    }
}
