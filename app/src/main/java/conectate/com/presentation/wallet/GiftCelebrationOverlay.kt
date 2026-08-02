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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import conectate.com.data.model.LiveGiftEvent
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.visualKey
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val MAX_GIFT_QUEUE_SIZE = 2

/**
 * Mantiene como máximo un regalo protagonista y uno en espera. Los IDs vistos
 * no vuelven a reproducirse cuando Realtime recompone la misma lista.
 */
internal class GiftEventQueue(private val capacity: Int = MAX_GIFT_QUEUE_SIZE) {
    private val queued = ArrayDeque<LiveGiftEvent>()
    private val seen = LinkedHashSet<String>()

    fun offerAll(events: List<LiveGiftEvent>) {
        events.forEach { event ->
            if (event.transactionId.isBlank() || !seen.add(event.transactionId)) return@forEach
            if (queued.size < capacity) queued.addLast(event)
        }
        while (seen.size > 100) seen.remove(seen.first())
    }

    fun peek(): LiveGiftEvent? = queued.firstOrNull()

    fun complete(transactionId: String) {
        if (queued.firstOrNull()?.transactionId == transactionId) queued.removeFirst()
    }

    fun size(): Int = queued.size
}

@Composable
fun GiftCelebrationOverlay(events: List<LiveGiftEvent>, modifier: Modifier = Modifier) {
    val queue = remember { GiftEventQueue() }
    var protagonist by remember { mutableStateOf<LiveGiftEvent?>(null) }

    LaunchedEffect(events.map { it.transactionId }) {
        queue.offerAll(events)
        if (protagonist == null) protagonist = queue.peek()
    }

    LaunchedEffect(protagonist?.transactionId) {
        val event = protagonist ?: return@LaunchedEffect
        delay(giftAnimationDurationMs(event.gift.priceDiamonds).toLong() + 120L)
        queue.complete(event.transactionId)
        protagonist = queue.peek()
    }

    val event = protagonist ?: return
    GiftAnimationStage(
        gift = event.gift,
        senderLabel = "${event.senderName} envió este regalo",
        runKey = event.transactionId,
        preview = false,
        modifier = modifier
    )
}

/** La vista previa y el evento confirmado comparten exactamente el mismo motor. */
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
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }
    val tier = giftAnimationTier(gift.priceDiamonds)
    val duration = giftAnimationDurationMs(gift.priceDiamonds)
    val motion = giftMotionProfile(gift.visualKey())
    val progress = remember(runKey) { Animatable(if (animationsEnabled) 0f else 0.58f) }

    LaunchedEffect(runKey, animationsEnabled) {
        if (animationsEnabled) progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
    }

    val value = progress.value
    val fade = when {
        !animationsEnabled -> 1f
        value < 0.10f -> value / 0.10f
        preview && value > 0.90f -> ((1f - value) / 0.10f).coerceIn(0f, 1f)
        !preview && value > 0.86f -> ((1f - value) / 0.14f).coerceIn(0f, 1f)
        else -> 1f
    }
    val transform = giftMotionTransform(motion, value, animationsEnabled)
    val colors = listOf(
        Color(0xFFFF4F87),
        Color(0xFF8F67FF),
        Color(0xFF35D7FF),
        Color(0xFFFFC857)
    )
    val particleCount = when (motion) {
        GiftMotionProfile.GENESIS -> 40
        GiftMotionProfile.ASCENT -> 34
        GiftMotionProfile.KINGDOM, GiftMotionProfile.PALACE -> 30
        else -> listOf(8, 16, 24)[tier]
    }
    val artSize = when (motion) {
        GiftMotionProfile.GENESIS -> 216.dp
        GiftMotionProfile.ASCENT, GiftMotionProfile.KINGDOM -> 204.dp
        else -> listOf(104.dp, 148.dp, 188.dp)[tier]
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (tier == 2) {
                    Modifier.background(
                        Brush.radialGradient(
                            listOf(Color(0x668F67FF).copy(alpha = fade * 0.42f), Color.Transparent)
                        )
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (animationsEnabled) {
            Canvas(Modifier.fillMaxSize().alpha(fade)) {
                repeat(particleCount) { index ->
                    val phase = motion.ordinal * 0.31f
                    val angle = index * (PI * 2 / particleCount) - PI / 2 + value * PI + phase
                    val wave = 0.85f + 0.15f * sin(value * PI * 4 + index)
                    val distance = size.minDimension *
                        (0.04f + value * (0.18f + tier * 0.075f)) * wave.toFloat()
                    val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * distance
                    val radius = (2.6f + (index % 4) * 1.45f + tier) * density
                    drawCircle(colors[(index + motion.ordinal) % colors.size].copy(alpha = fade), radius, point)
                    if (tier == 2 && index % 4 == 0) {
                        drawLine(
                            color = colors[(index + 1) % colors.size].copy(alpha = fade * 0.62f),
                            start = center,
                            end = point,
                            strokeWidth = 1.2f * density
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    rotationZ = transform.rotation
                    translationX = transform.translationX * density
                    translationY = transform.translationY * density
                }
                .alpha(fade)
                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(28.dp))
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

internal enum class GiftMotionProfile {
    SEED,
    DROP,
    SPARK,
    BUTTERFLY,
    FLOWER,
    MOON,
    HUMMINGBIRD,
    JELLYFISH,
    FOX,
    WHALE,
    GARDEN,
    ORCHID,
    SHIP,
    GUARDIAN,
    PHOENIX,
    TRAIN,
    DRAGON,
    ISLAND,
    WORLD,
    OPERA,
    PALACE,
    KINGDOM,
    ASCENT,
    GENESIS
}

internal data class GiftMotionTransform(
    val scale: Float,
    val rotation: Float,
    val translationX: Float,
    val translationY: Float
)

internal fun giftMotionProfile(visualKey: String): GiftMotionProfile = when (visualKey) {
    "aurora-drop" -> GiftMotionProfile.DROP
    "prism-spark" -> GiftMotionProfile.SPARK
    "luminous-butterfly" -> GiftMotionProfile.BUTTERFLY
    "comet-flower" -> GiftMotionProfile.FLOWER
    "crystal-moon" -> GiftMotionProfile.MOON
    "solar-hummingbird" -> GiftMotionProfile.HUMMINGBIRD
    "nebula-jellyfish" -> GiftMotionProfile.JELLYFISH
    "boreal-fox" -> GiftMotionProfile.FOX
    "cosmic-whale" -> GiftMotionProfile.WHALE
    "star-garden" -> GiftMotionProfile.GARDEN
    "crystal-orchid" -> GiftMotionProfile.ORCHID
    "prism-ship" -> GiftMotionProfile.SHIP
    "forest-guardian" -> GiftMotionProfile.GUARDIAN
    "aurora-phoenix" -> GiftMotionProfile.PHOENIX
    "star-train" -> GiftMotionProfile.TRAIN
    "celestial-dragon" -> GiftMotionProfile.DRAGON
    "floating-island" -> GiftMotionProfile.ISLAND
    "celestial-world" -> GiftMotionProfile.WORLD
    "light-opera" -> GiftMotionProfile.OPERA
    "aurora-palace" -> GiftMotionProfile.PALACE
    "time-kingdom" -> GiftMotionProfile.KINGDOM
    "galactic-ascent" -> GiftMotionProfile.ASCENT
    "aurora-genesis" -> GiftMotionProfile.GENESIS
    else -> GiftMotionProfile.SEED
}

internal fun giftMotionTransform(
    profile: GiftMotionProfile,
    progress: Float,
    animationsEnabled: Boolean = true
): GiftMotionTransform {
    if (!animationsEnabled) return GiftMotionTransform(1f, 0f, 0f, 0f)
    val p = progress.coerceIn(0f, 1f)
    val entry = (p / 0.24f).coerceIn(0f, 1f)
    val pulse = sin(p * PI * 4).toFloat()
    return when (profile) {
        GiftMotionProfile.SEED -> GiftMotionTransform(0.42f + entry * 0.58f + abs(pulse) * 0.05f, pulse * 2f, 0f, 0f)
        GiftMotionProfile.DROP -> GiftMotionTransform(0.74f + entry * 0.26f, pulse * 3f, 0f, -72f * (1f - entry))
        GiftMotionProfile.SPARK -> GiftMotionTransform(0.35f + entry * 0.65f + abs(pulse) * 0.08f, p * 80f, 0f, 0f)
        GiftMotionProfile.BUTTERFLY -> GiftMotionTransform(0.68f + entry * 0.32f, pulse * 12f, pulse * 18f, -abs(pulse) * 10f)
        GiftMotionProfile.FLOWER -> GiftMotionTransform(0.40f + entry * 0.60f, p * 38f, 0f, 0f)
        GiftMotionProfile.MOON -> GiftMotionTransform(0.67f + entry * 0.33f, p * 120f, cos(p * PI * 2).toFloat() * 16f, sin(p * PI * 2).toFloat() * 9f)
        GiftMotionProfile.HUMMINGBIRD -> GiftMotionTransform(0.66f + entry * 0.34f, pulse * 8f, -62f * (1f - entry), pulse * 10f)
        GiftMotionProfile.JELLYFISH -> GiftMotionTransform(0.70f + entry * 0.30f, pulse * 3f, pulse * 8f, 45f * (1f - entry) - abs(pulse) * 8f)
        GiftMotionProfile.FOX -> GiftMotionTransform(0.68f + entry * 0.32f, -10f + entry * 10f, 42f * (1f - entry), -abs(sin(p * PI).toFloat()) * 18f)
        GiftMotionProfile.WHALE -> GiftMotionTransform(0.68f + entry * 0.32f, pulse * 3f, -54f * (1f - entry) + pulse * 8f, sin(p * PI).toFloat() * -14f)
        GiftMotionProfile.GARDEN -> GiftMotionTransform(0.44f + entry * 0.56f, pulse * 2f, 0f, 58f * (1f - entry))
        GiftMotionProfile.ORCHID -> GiftMotionTransform(0.34f + entry * 0.66f, pulse * 4f, 0f, -abs(pulse) * 5f)
        GiftMotionProfile.SHIP -> GiftMotionTransform(0.66f + entry * 0.34f, -24f + entry * 24f, -74f * (1f - entry), 54f * (1f - entry))
        GiftMotionProfile.GUARDIAN -> GiftMotionTransform(0.62f + entry * 0.38f, pulse * 2f, 0f, 42f * (1f - entry))
        GiftMotionProfile.PHOENIX -> GiftMotionTransform(0.58f + entry * 0.42f, pulse * 7f, pulse * 12f, 70f * (1f - entry))
        GiftMotionProfile.TRAIN -> GiftMotionTransform(0.67f + entry * 0.33f, pulse * 2f, -88f * (1f - entry), 0f)
        GiftMotionProfile.DRAGON -> GiftMotionTransform(0.58f + entry * 0.42f, 22f * (1f - entry) + pulse * 5f, 62f * (1f - entry), -38f * (1f - entry))
        GiftMotionProfile.ISLAND -> GiftMotionTransform(0.58f + entry * 0.42f, pulse * 2f, 0f, 62f * (1f - entry) + pulse * 5f)
        GiftMotionProfile.WORLD -> GiftMotionTransform(0.54f + entry * 0.46f, p * 72f, 0f, 0f)
        GiftMotionProfile.OPERA -> GiftMotionTransform(0.56f + entry * 0.44f, pulse * 6f, pulse * 12f, -abs(pulse) * 8f)
        GiftMotionProfile.PALACE -> GiftMotionTransform(0.64f + entry * 0.36f, 0f, 0f, 58f * (1f - entry))
        GiftMotionProfile.KINGDOM -> GiftMotionTransform(0.46f + entry * 0.54f, p * 18f, 0f, 36f * (1f - entry))
        GiftMotionProfile.ASCENT -> GiftMotionTransform(0.44f + entry * 0.56f, pulse * 4f, 0f, 88f * (1f - entry))
        GiftMotionProfile.GENESIS -> GiftMotionTransform(0.22f + entry * 0.78f + abs(pulse) * 0.06f, p * 140f, 0f, 0f)
    }
}

internal fun giftAnimationDurationMs(priceDiamonds: Int): Int {
    val price = priceDiamonds.coerceAtLeast(0)
    return when (giftAnimationTier(price)) {
        0 -> (700 + price / 100f * 500).toInt().coerceIn(700, 1_200)
        1 -> (1_200 + (price - 100) / 800f * 1_200).toInt().coerceIn(1_200, 2_400)
        else -> (2_600 + (price - 900) / 4_100f * 2_200).toInt().coerceIn(2_600, 4_800)
    }
}

internal fun giftAnimationTier(priceDiamonds: Int): Int = when {
    priceDiamonds < 100 -> 0
    priceDiamonds < 900 -> 1
    else -> 2
}
