package conectate.com.presentation.wallet

import android.content.Context
import android.app.ActivityManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import conectate.com.data.local.prefs.AppSettingsPreferences
import conectate.com.data.model.LiveGiftEvent
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.visualKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val STATIC_FALLBACK_MS = 300L
private val GiftPink = Color(0xFFFF4F87)
private val GiftPurple = Color(0xFF8F67FF)
private val GiftCyan = Color(0xFF35D7FF)
private val GiftGold = Color(0xFFFFC857)
private val GiftMint = Color(0xFF65F5B5)
private val GiftPalette = listOf(GiftPink, GiftPurple, GiftCyan, GiftGold, GiftMint)

/**
 * Reproduce una celebración a la vez y conserva la ráfaga en una cola acotada.
 * El historial del directo no caduca al terminar una animación.
 */
@Composable
fun GiftCelebrationOverlay(
    events: List<LiveGiftEvent>,
    modifier: Modifier = Modifier,
    sessionKey: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val queue = remember(sessionKey) { GiftEventQueue() }
    val celebrationStartMs = remember(sessionKey) { System.currentTimeMillis() - 10_000L }
    val latestEvents by rememberUpdatedState(events)
    var protagonist by remember(sessionKey) { mutableStateOf<LiveGiftEvent?>(null) }
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycleOwner, queue) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                GiftSoundEngine.stopAll()
                queue.discardAll(latestEvents)
                protagonist = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            GiftSoundEngine.stopAll()
            queue.clear()
        }
    }

    LaunchedEffect(events.map { it.transactionId }, foreground, queue) {
        if (!foreground) {
            queue.discardAll(events)
            protagonist = null
            return@LaunchedEffect
        }
        queue.offerAll(events.filter {
            (sessionKey == null || it.liveStreamId == sessionKey) && it.createdAtMs >= celebrationStartMs
        })
        if (protagonist == null) protagonist = queue.peek()
    }

    LaunchedEffect(protagonist?.transactionId, foreground, queue) {
        if (!foreground) return@LaunchedEffect
        val event = protagonist ?: return@LaunchedEffect
        val profile = giftMotionProfile(event.gift.visualKey())
        val animationsEnabled = giftAnimationsEnabled(context)
        val renderQuality = giftRenderQuality(context)
        val cinematicVideo = usesCinematicGiftVideo(event.gift, animationsEnabled, renderQuality)
        // LiveGiftEvent sólo se crea tras el commit/ack del servidor. La vista
        // previa llama GiftAnimationStage directamente y jamás llega aquí.
        // El MP4 cinematográfico ya lleva su rugido; no superponerle el tono sintético.
        if (!cinematicVideo) {
            GiftSoundEngine.playConfirmed(context.applicationContext, giftSceneSpec(profile).sound)
        }
        val displayDuration = if (animationsEnabled) {
            if (cinematicVideo) CINEMATIC_GIFT_VIDEO_DURATION_MS.toLong()
            else giftAnimationDurationMs(event.gift.priceDiamonds).toLong() + 120L
        } else {
            STATIC_FALLBACK_MS
        }
        delay(displayDuration)
        queue.complete(event.transactionId)
        // Si una ráfaga superó la cola visible, toma el siguiente confirmado
        // al liberar espacio sin reproducir IDs ya celebrados.
        queue.offerAll(latestEvents.filter {
            (sessionKey == null || it.liveStreamId == sessionKey) && it.createdAtMs >= celebrationStartMs
        })
        protagonist = queue.peek()
    }

    val event = protagonist ?: return
    GiftAnimationStage(
        gift = event.gift,
        senderLabel = "${event.senderName} envió ${event.gift.name}",
        runKey = event.transactionId,
        preview = false,
        modifier = modifier
    )
}

/** La vista previa y el evento confirmado comparten exactamente el motor visual. */
@Composable
fun GiftAnimationStage(
    gift: VirtualGift,
    senderLabel: String,
    runKey: Any,
    preview: Boolean,
    modifier: Modifier = Modifier,
    showCaption: Boolean = true,
) {
    val context = LocalContext.current
    val animationsEnabled = remember(runKey) { giftAnimationsEnabled(context) }
    val renderQuality = remember(context) { giftRenderQuality(context) }
    val cinematicVideo = usesCinematicGiftVideo(gift, animationsEnabled, renderQuality)
    val tier = giftAnimationTier(gift.priceDiamonds)
    val duration = if (cinematicVideo) CINEMATIC_GIFT_VIDEO_DURATION_MS else giftAnimationDurationMs(gift.priceDiamonds)
    val profile = giftMotionProfile(gift.visualKey())
    val spec = remember(profile, renderQuality) {
        giftSceneForQuality(giftSceneSpec(profile), renderQuality)
    }
    val progress = remember(runKey) { Animatable(if (animationsEnabled) 0f else 0.58f) }
    var cinematicVideoFailed by remember(runKey) { mutableStateOf(false) }
    val showCinematicVideo = cinematicVideo && !cinematicVideoFailed

    LaunchedEffect(runKey, animationsEnabled, duration, showCinematicVideo) {
        if (animationsEnabled) {
            progress.animateTo(
                1f,
                tween(duration, easing = if (showCinematicVideo) LinearEasing else FastOutSlowInEasing)
            )
        }
    }

    val value = progress.value
    val fade = when {
        !animationsEnabled -> 1f
        value < 0.08f -> value / 0.08f
        preview && value > 0.91f -> ((1f - value) / 0.09f).coerceIn(0f, 1f)
        !preview && value > 0.88f -> ((1f - value) / 0.12f).coerceIn(0f, 1f)
        else -> 1f
    }
    val transform = giftMotionTransform(profile, value, animationsEnabled)
    // Las poses del atlas y la articulación secundaria se complementan. La
    // versión anterior desactivaba esta articulación para los 24 regalos.
    val articulation = giftArticulationTransform(profile, value, animationsEnabled)
    val baseArtSize = when (tier) {
        0 -> 112.dp
        1 -> 158.dp
        else -> 198.dp
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (spec.fullScreen) {
                    Modifier.background(
                        Brush.radialGradient(
                            listOf(
                                GiftPurple.copy(alpha = fade * 0.32f),
                                Color.Black.copy(alpha = fade * 0.16f),
                                Color.Transparent
                            )
                        )
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
        val ime = WindowInsets.ime.asPaddingValues()
        val viewportInsets = if (preview) {
            GiftViewportInsetsDp()
        } else {
            GiftViewportInsetsDp(
                startDp = safeDrawing.calculateStartPadding(layoutDirection).value,
                topDp = safeDrawing.calculateTopPadding().value,
                endDp = safeDrawing.calculateEndPadding(layoutDirection).value,
                bottomDp = maxOf(
                    safeDrawing.calculateBottomPadding().value,
                    ime.calculateBottomPadding().value
                )
            )
        }
        val layout = calculateGiftCelebrationLayout(
            viewportWidthDp = maxWidth.value,
            viewportHeightDp = maxHeight.value,
            insets = viewportInsets,
            baseArtworkSizeDp = baseArtSize.value,
            artworkScale = spec.artScale,
            motion = transform,
            preview = preview,
            showCaption = showCaption
        )
        val artworkTranslationX = with(density) { layout.artworkTranslationXDp.dp.toPx() }
        val artworkTranslationY = with(density) { layout.artworkTranslationYDp.dp.toPx() }
        val artworkSizePx = with(density) { layout.artworkSizeDp.dp.toPx() }
        val cinematicSize = minOf(maxWidth * 0.88f, maxHeight * 0.68f)

        Canvas(Modifier.fillMaxSize().alpha(fade)) {
            drawGiftEnvironment(profile, spec, value, animationsEnabled)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = viewportInsets.startDp.dp,
                    top = viewportInsets.topDp.dp,
                    end = viewportInsets.endDp.dp,
                    bottom = viewportInsets.bottomDp.dp
                )
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            if (showCinematicVideo) {
                Box(
                    modifier = Modifier
                        .size(cinematicSize)
                        .alpha(fade),
                    contentAlignment = Alignment.Center
                ) {
                    GiftCinematicVideo(
                        runKey = runKey,
                        modifier = Modifier.fillMaxSize(),
                        onPlaybackError = { cinematicVideoFailed = true }
                    )
                }
            } else if (layout.artworkSizeDp > 0f) {
                Box(
                    modifier = Modifier
                        .size(layout.artworkSizeDp.dp)
                        .graphicsLayer {
                            scaleX = transform.scale
                            scaleY = transform.scale
                            rotationZ = transform.rotation
                            translationX = artworkTranslationX
                            translationY = artworkTranslationY
                        }
                        .alpha(fade),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(
                                    GiftPalette[(profile.ordinal + 1) % GiftPalette.size].copy(alpha = 0.38f),
                                    Color.Transparent
                                )
                            ),
                            radius = size.minDimension * 0.54f
                        )
                    }
                    GiftAnimatedArtwork(
                        gift = gift,
                        progress = value,
                        animationsEnabled = animationsEnabled,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = articulation.scaleX
                                scaleY = articulation.scaleY
                                rotationZ = articulation.rotation
                                translationY = articulation.translationY * artworkSizePx
                            }
                    )
                }
            }

            if (showCaption && layout.captionMaxWidthDp > 0f) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = layout.captionMaxWidthDp.dp)
                        .padding(horizontal = if (layout.compactTypography) 12.dp else 18.dp)
                        .padding(bottom = layout.captionBottomPaddingDp.dp)
                        .alpha(fade)
                        .background(Color.Black.copy(alpha = 0.80f), RoundedCornerShape(22.dp))
                        .padding(
                            horizontal = if (layout.compactTypography) 16.dp else 20.dp,
                            vertical = if (layout.compactTypography) 8.dp else 11.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = gift.name,
                        color = Color.White,
                        style = if (layout.compactTypography) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = senderLabel,
                        color = Color.White.copy(alpha = 0.92f),
                        style = if (layout.compactTypography) {
                            MaterialTheme.typography.bodySmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

internal fun giftAnimationsEnabled(context: Context): Boolean {
    if (AppSettingsPreferences.isReduceMotionEnabled(context)) return false
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }.getOrDefault(true)
}

internal fun giftRenderQuality(context: Context): GiftRenderQuality {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return GiftRenderQuality.MEDIUM
    return when {
        manager.isLowRamDevice || manager.memoryClass <= 128 -> GiftRenderQuality.LOW
        manager.memoryClass <= 256 -> GiftRenderQuality.MEDIUM
        else -> GiftRenderQuality.HIGH
    }
}

private fun DrawScope.drawGiftEnvironment(
    profile: GiftMotionProfile,
    spec: GiftSceneSpec,
    progress: Float,
    animationsEnabled: Boolean
) {
    val p = if (animationsEnabled) progress.coerceIn(0f, 1f) else 0.58f
    val hero = heroPoint(profile, p, animationsEnabled)
    val phase = spec.effect.ordinal * 0.37f

    if (!animationsEnabled) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(GiftPalette[profile.ordinal % GiftPalette.size].copy(alpha = 0.28f), Color.Transparent),
                center = hero
            ),
            radius = size.minDimension * 0.30f,
            center = hero
        )
        return
    }

    if (spec.fullScreen) {
        repeat(spec.layerCount) { layer ->
            val layerProgress = (p * (0.7f + layer * 0.08f) + layer * 0.11f) % 1f
            drawCircle(
                color = GiftPalette[(profile.ordinal + layer) % GiftPalette.size]
                    .copy(alpha = (1f - layerProgress) * 0.16f),
                radius = size.minDimension * (0.12f + layerProgress * 0.72f),
                center = center,
                style = Stroke((1.2f + layer * 0.45f) * density)
            )
        }
    }

    when (spec.effect) {
        GiftSceneEffect.GERMINATION -> {
            drawLine(GiftMint.copy(alpha = 0.68f), Offset(center.x, size.height * 0.78f), hero, 4f * density)
            repeat(7) { index ->
                val y = size.height * (0.72f - index * 0.055f)
                val side = if (index % 2 == 0) -1f else 1f
                drawOval(
                    GiftPalette[index % GiftPalette.size].copy(alpha = 0.45f + p * 0.35f),
                    Offset(center.x + side * (8f + index) * density, y),
                    Size(12f * density, 6f * density)
                )
            }
        }

        GiftSceneEffect.RAIN_RIPPLE -> {
            repeat(9) { index ->
                val x = size.width * (0.08f + index * 0.105f)
                val drop = ((p * 1.8f + index * 0.13f) % 1f)
                drawLine(
                    GiftCyan.copy(alpha = 0.18f + drop * 0.45f),
                    Offset(x, size.height * (drop - 0.10f)),
                    Offset(x, size.height * (drop + 0.06f)),
                    1.4f * density
                )
            }
            repeat(4) { ring ->
                val ripple = ((p * 1.5f + ring * 0.22f) % 1f)
                drawOval(
                    GiftCyan.copy(alpha = (1f - ripple) * 0.55f),
                    Offset(center.x - size.width * ripple * 0.38f, center.y - 10f * density - size.height * ripple * 0.04f),
                    Size(size.width * ripple * 0.76f, size.height * ripple * 0.08f),
                    style = Stroke(1.6f * density)
                )
            }
        }

        GiftSceneEffect.PRISM_BURST -> repeat(18) { index ->
            val angle = index * PI * 2 / 18 + p * PI
            val inner = size.minDimension * 0.10f
            val outer = size.minDimension * (0.16f + 0.26f * p + (index % 3) * 0.025f)
            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(
                GiftPalette[index % GiftPalette.size].copy(alpha = 0.74f),
                hero + direction * inner,
                hero + direction * outer,
                (1.4f + index % 3) * density
            )
        }

        GiftSceneEffect.WING_TRAIL -> {
            drawPathTrail(profile, p, 18, GiftPink, 7f)
            repeat(6) { index ->
                val q = (p - index * 0.035f).coerceAtLeast(0f)
                val point = heroPoint(profile, q, true)
                val wing = (10f + index * 2f) * density
                drawArc(GiftCyan.copy(alpha = (1f - index / 6f) * 0.55f), 190f, 150f, false, point - Offset(wing, wing), Size(wing * 2, wing * 2), style = Stroke(1.2f * density))
            }
        }

        GiftSceneEffect.PETAL_COMET -> {
            drawPathTrail(profile, p, 18, GiftPink, 9f)
            repeat(18) { index ->
                val angle = index * 2.4f + p * 9f
                val distance = size.minDimension * (0.05f + (index % 6) * 0.025f)
                val point = hero + Offset(
                    cos(angle).toFloat() * distance,
                    sin(angle).toFloat() * distance
                )
                drawOval(GiftPalette[index % GiftPalette.size].copy(alpha = 0.62f), point, Size(7f * density, 12f * density))
            }
        }

        GiftSceneEffect.LUNAR_ORBITS -> {
            repeat(3) { orbit ->
                val radiusX = size.minDimension * (0.20f + orbit * 0.09f)
                val radiusY = radiusX * 0.42f
                rotate(-18f + orbit * 17f, center) {
                    drawOval(
                        GiftPalette[(orbit + 1) % GiftPalette.size].copy(alpha = 0.48f),
                        center - Offset(radiusX, radiusY),
                        Size(radiusX * 2, radiusY * 2),
                        style = Stroke((1.3f + orbit * 0.4f) * density)
                    )
                }
                val angle = p * PI * 2 * (orbit + 1) + orbit
                drawCircle(
                    GiftGold,
                    (3f + orbit) * density,
                    center + Offset(cos(angle).toFloat() * radiusX, sin(angle).toFloat() * radiusY)
                )
            }
        }

        GiftSceneEffect.GLITTER_DUST -> {
            // El colibrí atraviesa la escena; cada punto usa una posición pasada
            // de su trayectoria y produce una estela real, no un halo estático.
            repeat(spec.particleCount) { index ->
                val q = (p - index * 0.018f).coerceAtLeast(0f)
                val point = heroPoint(profile, q, true)
                val wobble = Offset(
                    sin(index * 2.13f + p * 18f) * (3f + index * 0.20f) * density,
                    cos(index * 1.71f + p * 14f) * (3f + index * 0.16f) * density
                )
                val alpha = (1f - index / spec.particleCount.toFloat()) * 0.92f
                drawSpark(point + wobble, (2.2f + index % 3) * density, GiftPalette[index % GiftPalette.size], alpha)
            }
        }

        GiftSceneEffect.NEBULA_TENTACLES -> {
            repeat(7) { strand ->
                var last = hero + Offset((strand - 3) * 5f * density, 18f * density)
                repeat(10) { step ->
                    val next = Offset(
                        hero.x + (strand - 3) * 7f * density + sin(p * 12f + step * 0.8f + strand) * 9f * density,
                        hero.y + (24f + step * 10f) * density
                    )
                    drawLine(GiftPalette[(strand + 2) % GiftPalette.size].copy(alpha = 0.42f), last, next, 1.4f * density)
                    last = next
                }
            }
        }

        GiftSceneEffect.AURORA_PAWS -> {
            drawPathTrail(profile, p, 20, GiftCyan, 11f)
            repeat(11) { index ->
                val q = (p - index * 0.06f).coerceAtLeast(0f)
                val point = heroPoint(profile, q, true) + Offset(0f, 54f * density)
                val alpha = (1f - index / 11f) * 0.58f
                drawCircle(GiftMint.copy(alpha = alpha), 4f * density, point)
                drawCircle(GiftMint.copy(alpha = alpha), 2f * density, point + Offset(-5f, -5f) * density)
                drawCircle(GiftMint.copy(alpha = alpha), 2f * density, point + Offset(5f, -5f) * density)
            }
        }

        GiftSceneEffect.COSMIC_WAKE -> {
            drawPathTrail(profile, p, 24, GiftCyan, 16f)
            repeat(8) { index ->
                val q = (p - index * 0.055f).coerceAtLeast(0f)
                val point = heroPoint(profile, q, true)
                drawArc(
                    GiftPurple.copy(alpha = (1f - index / 8f) * 0.48f),
                    170f,
                    170f,
                    false,
                    point - Offset(24f, 14f) * density,
                    Size(48f * density, 28f * density),
                    style = Stroke(1.4f * density)
                )
            }
        }

        GiftSceneEffect.BLOOM_FIELD -> repeat(15) { index ->
            val x = size.width * (0.04f + index / 15f * 0.94f)
            val bloom = ((p * 1.7f + index * 0.09f) % 1f)
            val y = size.height * (0.78f - (index % 4) * 0.05f)
            val radius = (3f + bloom * 9f) * density
            drawLine(GiftMint.copy(alpha = 0.48f), Offset(x, size.height * 0.91f), Offset(x, y), 1.3f * density)
            repeat(5) { petal ->
                val angle = petal * PI * 2 / 5
                drawCircle(
                    GiftPalette[(index + petal) % GiftPalette.size].copy(alpha = 0.64f),
                    radius * 0.42f,
                    Offset(x, y) + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * radius
                )
            }
        }

        GiftSceneEffect.CRYSTAL_PETALS -> repeat(20) { index ->
            val angle = index * PI * 2 / 20 + p * PI * 0.8
            val distance = size.minDimension * (0.10f + (index % 5) * 0.055f + p * 0.06f)
            val point = hero + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * distance
            val axis = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            drawLine(GiftPalette[index % GiftPalette.size].copy(alpha = 0.68f), point - axis * 8f * density, point + axis * 8f * density, 3f * density)
        }

        GiftSceneEffect.ENGINE_TRAIL -> {
            drawPathTrail(profile, p, 26, GiftPurple, 18f)
            repeat(4) { flame ->
                val direction = Offset(-0.70f, 0.70f)
                val start = hero + direction * (46f + flame * 6f) * density
                val end = start + direction * (38f + flame * 16f) * density
                drawLine(GiftPalette[(flame + 1) % GiftPalette.size].copy(alpha = 0.80f), start, end, (7f - flame) * density)
            }
        }

        GiftSceneEffect.FOREST_RUNES -> {
            drawPathTrail(profile, p, 24, GiftMint, 10f)
            repeat(10) { index ->
                val x = size.width * (0.06f + index * 0.10f)
                val glow = 0.22f + 0.38f * abs(sin(p * PI * 4 + index).toFloat())
                drawLine(GiftMint.copy(alpha = glow), Offset(x, size.height * 0.76f), Offset(x, size.height * 0.90f), 2f * density)
                drawCircle(GiftGold.copy(alpha = glow), 5f * density, Offset(x, size.height * 0.74f))
            }
        }

        GiftSceneEffect.AURORA_FLAMES -> {
            drawPathTrail(profile, p, 26, GiftPink, 14f)
            repeat(18) { index ->
                val angle = index * PI * 2 / 18 + phase
                val distance = size.minDimension * (0.08f + (index % 5) * 0.035f)
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                val start = hero + direction * distance
                val end = start + direction * (18f + index % 4 * 8f) * density
                drawLine(GiftPalette[index % GiftPalette.size].copy(alpha = 0.68f), start, end, (2f + index % 3) * density)
            }
        }

        GiftSceneEffect.STELLAR_RAIL -> {
            val railY = size.height * 0.67f
            drawLine(GiftCyan.copy(alpha = 0.72f), Offset(0f, railY - 10f * density), Offset(size.width, railY - 10f * density), 2.4f * density)
            drawLine(GiftPurple.copy(alpha = 0.72f), Offset(0f, railY + 12f * density), Offset(size.width, railY + 12f * density), 2.4f * density)
            repeat(18) { index ->
                val x = ((index / 18f + p * 1.8f) % 1f) * size.width
                drawLine(GiftGold.copy(alpha = 0.50f), Offset(x, railY - 16f * density), Offset(x, railY + 18f * density), 2f * density)
            }
            repeat(10) { index ->
                val q = (p - index * 0.025f).coerceAtLeast(0f)
                val point = heroPoint(profile, q, true) + Offset(-70f * density, -54f * density)
                drawCircle(Color.White.copy(alpha = (1f - index / 10f) * 0.32f), (8f + index * 1.6f) * density, point - Offset(index * 5f, index * 7f) * density)
            }
        }

        GiftSceneEffect.CELESTIAL_ARC -> {
            drawPathTrail(profile, p, 30, GiftCyan, 18f)
            repeat(4) { ring ->
                val radius = size.minDimension * (0.15f + ring * 0.11f)
                drawArc(
                    GiftPalette[(ring + 1) % GiftPalette.size].copy(alpha = 0.42f),
                    -160f + p * 220f,
                    120f + ring * 18f,
                    false,
                    center - Offset(radius, radius),
                    Size(radius * 2, radius * 2),
                    style = Stroke((1.5f + ring * 0.4f) * density)
                )
            }
        }

        GiftSceneEffect.CLOUD_ISLAND -> {
            repeat(9) { index ->
                val x = center.x + (index - 4) * 28f * density
                val y = hero.y + (84f + abs(index - 4) * 4f) * density
                drawCircle(Color.White.copy(alpha = 0.13f + (index % 3) * 0.04f), (32f + index % 3 * 9f) * density, Offset(x, y))
            }
            repeat(16) { index ->
                val x = size.width * ((index * 0.173f + p * 0.09f) % 1f)
                val y = size.height * (0.88f - ((p * 0.6f + index * 0.071f) % 1f) * 0.76f)
                drawCircle(GiftGold.copy(alpha = 0.42f), (2f + index % 3) * density, Offset(x, y))
            }
        }

        GiftSceneEffect.PLANET_RINGS -> repeat(5) { ring ->
            val radiusX = size.minDimension * (0.17f + ring * 0.075f)
            val radiusY = radiusX * (0.34f + ring * 0.025f)
            rotate(-24f + ring * 13f + p * 18f, center) {
                drawOval(
                    GiftPalette[ring % GiftPalette.size].copy(alpha = 0.47f),
                    center - Offset(radiusX, radiusY),
                    Size(radiusX * 2, radiusY * 2),
                    style = Stroke((1.5f + ring * 0.35f) * density)
                )
            }
        }

        GiftSceneEffect.LIGHT_CURTAINS -> {
            repeat(12) { index ->
                val x = size.width * (index + 0.5f) / 12f
                val wave = 0.5f + 0.5f * sin(p * PI * 4 + index * 0.7f).toFloat()
                drawLine(
                    GiftPalette[index % GiftPalette.size].copy(alpha = 0.12f + wave * 0.24f),
                    Offset(x, 0f),
                    Offset(x + sin(index + p * 8f) * 32f * density, size.height),
                    (8f + wave * 12f) * density
                )
            }
            repeat(5) { note ->
                val x = size.width * (0.18f + note * 0.16f)
                val y = size.height * (0.72f - ((p + note * 0.14f) % 1f) * 0.55f)
                drawCircle(GiftGold.copy(alpha = 0.72f), 7f * density, Offset(x, y))
                drawLine(GiftGold.copy(alpha = 0.72f), Offset(x + 7f * density, y), Offset(x + 7f * density, y - 28f * density), 3f * density)
            }
        }

        GiftSceneEffect.PALACE_BEAMS -> {
            repeat(9) { index ->
                val x = size.width * (index + 0.5f) / 9f
                val target = hero + Offset((index - 4) * 13f * density, -55f * density)
                drawLine(GiftPalette[index % GiftPalette.size].copy(alpha = 0.24f), Offset(x, size.height), target, (3f + index % 3 * 2f) * density)
            }
            repeat(4) { level ->
                val y = size.height * (0.76f - level * 0.07f)
                drawLine(GiftGold.copy(alpha = 0.38f), Offset(size.width * (0.20f + level * 0.04f), y), Offset(size.width * (0.80f - level * 0.04f), y), 2f * density)
            }
        }

        GiftSceneEffect.CLOCKWORK_RINGS -> {
            repeat(5) { ring ->
                val radius = size.minDimension * (0.15f + ring * 0.085f)
                drawCircle(GiftPalette[ring % GiftPalette.size].copy(alpha = 0.42f), radius, center, style = Stroke((1.5f + ring * 0.4f) * density))
                repeat(12) { tick ->
                    val angle = tick * PI * 2 / 12 + p * (if (ring % 2 == 0) 1 else -1) * PI
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    val inner = center + direction * (radius - 8f * density)
                    val outer = center + direction * (radius + 6f * density)
                    drawLine(GiftGold.copy(alpha = 0.52f), inner, outer, 1.5f * density)
                }
            }
        }

        GiftSceneEffect.GALAXY_LIFT -> {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, GiftCyan.copy(alpha = 0.23f), Color.Transparent)),
                topLeft = Offset(center.x - size.width * 0.18f, 0f),
                size = Size(size.width * 0.36f, size.height)
            )
            repeat(32) { index ->
                val q = ((p * 1.7f + index * 0.047f) % 1f)
                val angle = q * PI * 5 + index * 0.31f
                val radius = size.minDimension * (0.04f + q * 0.34f)
                val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * radius +
                    Offset(0f, size.height * (0.36f - q * 0.72f))
                drawSpark(point, (2.4f + index % 4) * density, GiftPalette[index % GiftPalette.size], 0.40f + q * 0.45f)
            }
        }

        GiftSceneEffect.GENESIS_WAVE -> {
            repeat(7) { ring ->
                val wave = ((p * 1.25f + ring * 0.13f) % 1f)
                drawCircle(
                    GiftPalette[(ring + profile.ordinal) % GiftPalette.size].copy(alpha = (1f - wave) * 0.62f),
                    size.minDimension * (0.05f + wave * 0.72f),
                    center,
                    style = Stroke((1.4f + ring * 0.45f) * density)
                )
            }
            repeat(28) { index ->
                val angle = index * PI * 2 / 28 + p * PI * 1.5f
                val inner = size.minDimension * (0.08f + p * 0.06f)
                val outer = size.minDimension * (0.32f + p * 0.52f + index % 4 * 0.025f)
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(
                    GiftPalette[index % GiftPalette.size].copy(alpha = 0.54f),
                    center + direction * inner,
                    center + direction * outer,
                    (1.2f + index % 4 * 0.5f) * density
                )
            }
        }
    }

    // Campo ambiental determinista. La semilla depende de la escena; no usa
    // Random por frame y evita parpadeos o resultados distintos por dispositivo.
    repeat(spec.particleCount) { index ->
        val angle = index * PI * 2 / spec.particleCount + p * PI * (0.45f + spec.layerCount * 0.08f) + phase
        val radialWave = 0.78f + 0.22f * sin(p * PI * 4 + index * 1.7f).toFloat()
        val distance = size.minDimension * (0.10f + (index % 7) * 0.032f + p * 0.10f) * radialWave
        val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * distance
        val radius = (1.4f + index % 4 * 0.85f + spec.layerCount * 0.22f) * density
        drawCircle(
            GiftPalette[(index + profile.ordinal) % GiftPalette.size].copy(alpha = 0.30f + (index % 3) * 0.12f),
            radius,
            point
        )
    }
}

private fun DrawScope.drawPathTrail(
    profile: GiftMotionProfile,
    progress: Float,
    samples: Int,
    color: Color,
    widthDp: Float
) {
    var previous: Offset? = null
    repeat(samples) { index ->
        val q = (progress - (samples - 1 - index) * 0.025f).coerceAtLeast(0f)
        val point = heroPoint(profile, q, true)
        previous?.let {
            drawLine(
                color.copy(alpha = 0.10f + index / samples.toFloat() * 0.48f),
                it,
                point,
                (1f + index / samples.toFloat() * widthDp) * density
            )
        }
        previous = point
    }
}

private fun DrawScope.heroPoint(
    profile: GiftMotionProfile,
    progress: Float,
    animationsEnabled: Boolean
): Offset {
    val transform = giftMotionTransform(profile, progress, animationsEnabled)
    return center + Offset(
        transform.translationX * size.width * 0.5f,
        transform.translationY * size.height * 0.5f
    )
}

private fun DrawScope.drawSpark(point: Offset, radius: Float, color: Color, alpha: Float) {
    val safeAlpha = alpha.coerceIn(0f, 1f)
    drawCircle(color.copy(alpha = safeAlpha * 0.75f), radius * 0.42f, point)
    drawLine(color.copy(alpha = safeAlpha), point - Offset(radius, 0f), point + Offset(radius, 0f), radius * 0.22f)
    drawLine(color.copy(alpha = safeAlpha), point - Offset(0f, radius), point + Offset(0f, radius), radius * 0.22f)
}
