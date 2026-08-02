package conectate.com.presentation.wallet

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import conectate.com.data.model.LiveGiftEvent
import conectate.com.data.model.VirtualGift
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GiftCelebrationOverlay(events: List<LiveGiftEvent>, modifier: Modifier = Modifier) {
    val event = events.lastOrNull() ?: return
    GiftAnimationStage(
        gift = event.gift,
        senderLabel = "${event.senderName} envió este regalo",
        runKey = event.transactionId,
        preview = false,
        modifier = modifier
    )
}

/** La vista previa y el evento confirmado comparten exactamente la misma animación. */
@Composable
fun GiftAnimationStage(
    gift: VirtualGift,
    senderLabel: String,
    runKey: Any,
    preview: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val animationsEnabled = remember(runKey) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
    val tier = giftAnimationTier(gift.priceDiamonds)
    val duration = when (tier) {
        0 -> 1_600
        1 -> 2_500
        else -> 3_600
    }
    val progress = remember(runKey) { Animatable(if (animationsEnabled) 0f else 0.55f) }

    LaunchedEffect(runKey, animationsEnabled) {
        if (animationsEnabled) progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
    }

    val fade = when {
        !animationsEnabled -> 1f
        progress.value < 0.12f -> progress.value / 0.12f
        preview && progress.value > 0.9f -> ((1f - progress.value) / 0.1f).coerceIn(0f, 1f)
        !preview && progress.value > 0.82f -> ((1f - progress.value) / 0.18f).coerceIn(0f, 1f)
        else -> 1f
    }
    val entryProgress = (progress.value / 0.3f).coerceIn(0f, 1f)
    val scale = if (animationsEnabled) 0.68f + entryProgress * 0.32f else 1f
    val rotation = when (tier) {
        0 -> sin(progress.value * PI * 4).toFloat() * 4f
        1 -> sin(progress.value * PI * 3).toFloat() * 8f
        else -> (progress.value - 0.5f) * 22f
    }
    val colors = listOf(Color(0xFFFF4F87), Color(0xFF8F67FF), Color(0xFF35D7FF), Color(0xFFFFC857))
    val particleCount = listOf(8, 16, 28)[tier]
    val artSize = listOf(112.dp, 152.dp, 190.dp)[tier]

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (tier == 2) Modifier.background(
                    Brush.radialGradient(
                        listOf(Color(0x668F67FF).copy(alpha = fade * 0.4f), Color.Transparent)
                    )
                ) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (animationsEnabled) {
            Canvas(Modifier.fillMaxSize().alpha(fade)) {
                repeat(particleCount) { index ->
                    val angle = index * (PI * 2 / particleCount) - PI / 2 + progress.value * PI
                    val distance = size.minDimension * (0.05f + progress.value * (0.22f + tier * 0.1f))
                    val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * distance
                    val radius = (3f + (index % 4) * 1.8f + tier) * density
                    drawCircle(colors[index % colors.size].copy(alpha = fade), radius, point)
                    if (tier == 2 && index % 3 == 0) {
                        drawLine(
                            color = colors[(index + 1) % colors.size].copy(alpha = fade * 0.75f),
                            start = center,
                            end = point,
                            strokeWidth = 1.5f * density
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                }
                .alpha(fade)
                .background(Color.Black.copy(alpha = 0.76f), RoundedCornerShape(28.dp))
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GiftArtwork(gift, Modifier.size(artSize))
            Text(
                text = gift.name,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Text(
                text = senderLabel,
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

internal fun giftAnimationTier(priceDiamonds: Int): Int = when {
    priceDiamonds < 50 -> 0
    priceDiamonds < 250 -> 1
    else -> 2
}
