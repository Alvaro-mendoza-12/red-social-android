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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import conectate.com.data.model.LiveGiftEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GiftCelebrationOverlay(
    events: List<LiveGiftEvent>,
    modifier: Modifier = Modifier
) {
    val event = events.lastOrNull() ?: return
    val context = LocalContext.current
    val animationsEnabled = remember(event.transactionId) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
    }
    val progress = remember(event.transactionId) { Animatable(if (animationsEnabled) 0f else 0.55f) }

    LaunchedEffect(event.transactionId, animationsEnabled) {
        if (animationsEnabled) {
            progress.animateTo(1f, tween(durationMillis = 3_200, easing = FastOutSlowInEasing))
        }
    }

    val fade = when {
        !animationsEnabled -> 1f
        progress.value < 0.12f -> progress.value / 0.12f
        progress.value > 0.82f -> ((1f - progress.value) / 0.18f).coerceIn(0f, 1f)
        else -> 1f
    }
    val scale = if (animationsEnabled) (0.72f + progress.value.coerceAtMost(0.35f) * 0.8f) else 1f

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (animationsEnabled) {
            Canvas(Modifier.fillMaxSize().alpha(fade)) {
                repeat(12) { index ->
                    val angle = index * (PI * 2 / 12) - PI / 2
                    val distance = size.minDimension * (0.08f + progress.value * 0.42f)
                    val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * distance
                    drawCircle(
                        color = listOf(Color(0xFFFF4F87), Color(0xFF8F67FF), Color(0xFF35D7FF), Color(0xFFFFC857))[index % 4].copy(alpha = fade),
                        radius = (4f + (index % 3) * 2f) * density,
                        center = point
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .scale(scale)
                .alpha(fade)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(28.dp))
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GiftArtwork(event.gift.id, Modifier.size(150.dp))
            Text(
                text = event.gift.name,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Text(
                text = "${event.senderName} envió este regalo",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}
