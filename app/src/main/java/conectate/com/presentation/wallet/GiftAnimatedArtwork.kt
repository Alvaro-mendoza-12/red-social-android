package conectate.com.presentation.wallet

import androidx.annotation.DrawableRes
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import conectate.com.R
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.visualKey
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object GiftSpriteSheetCache {
    // Los atlas son recursos nodpi: la misma imagen sirve para todas las
    // densidades. Compartirlos evita decodificar el mismo grupo por cada regalo.
    private val sheets = object : LruCache<Int, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ImageBitmap): Int =
            (value.width.toLong() * value.height * 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun getOrDecode(resources: android.content.res.Resources, @DrawableRes drawableRes: Int): ImageBitmap? =
        synchronized(sheets) {
            sheets.get(drawableRes) ?: runCatching {
                ImageBitmap.imageResource(resources, drawableRes).also { sheets.put(drawableRes, it) }
            }.getOrNull()
        }
}

/**
 * Secuencia de poses dibujadas: no deforma ni traslada una miniatura estática.
 * Cada celda contiene una postura distinta del personaje.
 */
internal data class GiftSpriteSheetSpec(
    @param:DrawableRes val drawableRes: Int,
    val columns: Int = 4,
    val rows: Int = 2,
    val frameCount: Int = 8,
    val cycles: Float,
    val firstRow: Int = 0,
    // Cada pose se mezcla mediante una curva continua. Ocho pasos por pose
    // garantizan al menos 64 estados visuales incluso en los atlas de 8 poses.
    val interpolationSteps: Int = 8,
)

internal data class GiftSpriteBlend(
    val currentFrame: Int,
    val nextFrame: Int,
    val nextAlpha: Float,
)

internal fun giftSpriteSheetSpec(visualKey: String): GiftSpriteSheetSpec? = when (visualKey) {
    "starlight-seed" -> groupedSprite(R.drawable.gift_anim_group_1_sheet, row = 0, cycles = 1.25f)
    "aurora-drop" -> groupedSprite(R.drawable.gift_anim_group_1_sheet, row = 1, cycles = 2.5f)
    "prism-spark" -> groupedSprite(R.drawable.gift_anim_group_1_sheet, row = 2, cycles = 2.25f)
    "luminous-butterfly" -> groupedSprite(R.drawable.gift_anim_group_1_sheet, row = 3, cycles = 3.5f)
    "comet-flower" -> groupedSprite(R.drawable.gift_anim_group_2_sheet, row = 0, cycles = 1.4f)
    "crystal-moon" -> groupedSprite(R.drawable.gift_anim_group_2_sheet, row = 1, cycles = 2f)
    "nebula-jellyfish" -> groupedSprite(R.drawable.gift_anim_group_2_sheet, row = 2, cycles = 2.6f)
    "boreal-fox" -> groupedSprite(R.drawable.gift_anim_group_2_sheet, row = 3, cycles = 1.5f)
    "star-garden" -> groupedSprite(R.drawable.gift_anim_group_3_sheet, row = 0, cycles = 1.25f)
    "crystal-orchid" -> groupedSprite(R.drawable.gift_anim_group_3_sheet, row = 1, cycles = 1.35f)
    "prism-ship" -> groupedSprite(R.drawable.gift_anim_group_3_sheet, row = 2, cycles = 2f)
    "aurora-phoenix" -> groupedSprite(R.drawable.gift_anim_group_3_sheet, row = 3, cycles = 2.75f)
    "star-train" -> groupedSprite(R.drawable.gift_anim_group_4_sheet, row = 0, cycles = 2.5f)
    "floating-island" -> groupedSprite(R.drawable.gift_anim_group_4_sheet, row = 1, cycles = 1.5f)
    "celestial-world" -> groupedSprite(R.drawable.gift_anim_group_4_sheet, row = 2, cycles = 1.8f)
    "light-opera" -> groupedSprite(R.drawable.gift_anim_group_4_sheet, row = 3, cycles = 1.25f)
    "aurora-palace" -> groupedSprite(R.drawable.gift_anim_group_5_sheet, row = 0, cycles = 1.1f)
    "time-kingdom" -> groupedSprite(R.drawable.gift_anim_group_5_sheet, row = 1, cycles = 1.35f)
    "galactic-ascent" -> groupedSprite(R.drawable.gift_anim_group_5_sheet, row = 2, cycles = 1.05f)
    "aurora-genesis" -> groupedSprite(R.drawable.gift_anim_group_5_sheet, row = 3, cycles = 1.05f)
    "solar-hummingbird" -> GiftSpriteSheetSpec(
        drawableRes = R.drawable.gift_anim_solar_hummingbird_sheet,
        cycles = 5.5f,
        interpolationSteps = 8,
    )
    "forest-guardian" -> GiftSpriteSheetSpec(
        drawableRes = R.drawable.gift_anim_forest_guardian_sheet,
        cycles = 2.25f,
        interpolationSteps = 8,
    )
    "cosmic-whale" -> GiftSpriteSheetSpec(
        drawableRes = R.drawable.gift_anim_cosmic_whale_sheet,
        cycles = 2f,
        interpolationSteps = 8,
    )
    "celestial-dragon" -> GiftSpriteSheetSpec(
        drawableRes = R.drawable.gift_anim_celestial_dragon_sheet,
        cycles = 1.75f,
        interpolationSteps = 8,
    )
    else -> null
}

private fun groupedSprite(
    @DrawableRes drawableRes: Int,
    row: Int,
    cycles: Float
) = GiftSpriteSheetSpec(
    drawableRes = drawableRes,
    rows = 4,
    frameCount = 4,
    cycles = cycles,
    firstRow = row,
    // Los atlas agrupados contienen cuatro poses verdaderamente distintas.
    // La interpolación conserva esas poses y crea 64 estados de transición
    // fluidos; la escena, partículas y articulación siguen animándose a la
    // frecuencia real de la pantalla.
    interpolationSteps = 16,
)

internal fun giftSpriteFrame(
    progress: Float,
    animationsEnabled: Boolean,
    spec: GiftSpriteSheetSpec
): Int {
    if (!animationsEnabled) return 0
    val normalized = progress.coerceIn(0f, 0.999_999f)
    return floor(normalized * spec.frameCount * spec.cycles)
        .toInt()
        .mod(spec.frameCount)
}

/**
 * Interpola dos posturas distintas. El atlas sigue siendo compacto, pero el
 * cambio deja de ser un salto de 4/8 imágenes y pasa a una línea de tiempo
 * continua; los estados efectivos se pueden reducir sin cambiar la identidad.
 */
internal fun giftSpriteBlend(
    progress: Float,
    animationsEnabled: Boolean,
    spec: GiftSpriteSheetSpec,
): GiftSpriteBlend {
    if (!animationsEnabled || spec.frameCount <= 1) return GiftSpriteBlend(0, 0, 0f)
    val position = progress.coerceIn(0f, 0.999_999f) * spec.frameCount * spec.cycles
    val base = floor(position).toInt()
    val current = base.mod(spec.frameCount)
    val next = (current + 1).mod(spec.frameCount)
    val fraction = position - floor(position)
    val smoothAlpha = fraction * fraction * (3f - 2f * fraction)
    return GiftSpriteBlend(current, next, smoothAlpha.coerceIn(0f, 1f))
}

internal fun giftEffectiveTimelineStates(spec: GiftSpriteSheetSpec): Int =
    spec.frameCount * spec.interpolationSteps.coerceAtLeast(1)

/** Ajusta una pose dentro del lienzo sin deformar artes rectangulares. */
internal fun fitGiftSpriteFrame(source: IntSize, destination: IntSize): Pair<IntOffset, IntSize> {
    if (source.width <= 0 || source.height <= 0 || destination.width <= 0 || destination.height <= 0) {
        return IntOffset.Zero to IntSize.Zero
    }
    val scale = min(
        destination.width.toFloat() / source.width.toFloat(),
        destination.height.toFloat() / source.height.toFloat()
    )
    val width = (source.width * scale).roundToInt().coerceIn(1, destination.width)
    val height = (source.height * scale).roundToInt().coerceIn(1, destination.height)
    return IntOffset(
        x = (destination.width - width) / 2,
        y = (destination.height - height) / 2
    ) to IntSize(width, height)
}

@Composable
internal fun GiftAnimatedArtwork(
    gift: VirtualGift,
    progress: Float,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val visualKey = gift.visualKey()
    val spec = giftSpriteSheetSpec(visualKey)
    if (spec == null) {
        GiftArtwork(gift, modifier)
        return
    }

    // Las hojas ocupan unos 6 MiB descomprimidas. Decodificarlas en el hilo de
    // interfaz congelaba el primer regalo en dispositivos de gama de entrada.
    val resources = LocalResources.current
    val spriteSheet by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = resources,
        key2 = spec.drawableRes,
    ) {
        value = withContext(Dispatchers.IO) {
            GiftSpriteSheetCache.getOrDecode(resources, spec.drawableRes)
        }
    }
    val artwork = spriteSheet
    if (artwork == null) {
        GiftArtwork(gift, modifier)
        return
    }
    val blend = giftSpriteBlend(progress, animationsEnabled, spec)
    val frameWidth = remember(artwork.width, spec.columns) {
        artwork.width / spec.columns.coerceAtLeast(1)
    }
    val frameHeight = remember(artwork.height, spec.rows) {
        artwork.height / spec.rows.coerceAtLeast(1)
    }
    fun sourceOffset(frame: Int) = IntOffset(
        x = (frame % spec.columns) * frameWidth,
        y = (spec.firstRow + frame / spec.columns) * frameHeight,
    )

    Canvas(modifier = modifier) {
        val destination = IntSize(size.width.roundToInt(), size.height.roundToInt())
        val (destinationOffset, destinationSize) = fitGiftSpriteFrame(
            source = IntSize(frameWidth, frameHeight),
            destination = destination
        )
        if (destinationSize == IntSize.Zero) return@Canvas
        drawImage(
            image = artwork,
            srcOffset = sourceOffset(blend.currentFrame),
            srcSize = IntSize(frameWidth, frameHeight),
            dstOffset = destinationOffset,
            dstSize = destinationSize,
            alpha = 1f - blend.nextAlpha,
            filterQuality = FilterQuality.High,
        )
        if (blend.nextFrame != blend.currentFrame && blend.nextAlpha > 0f) {
            drawImage(
                image = artwork,
                srcOffset = sourceOffset(blend.nextFrame),
                srcSize = IntSize(frameWidth, frameHeight),
                dstOffset = destinationOffset,
                dstSize = destinationSize,
                alpha = blend.nextAlpha,
                filterQuality = FilterQuality.High,
            )
        }
    }
}
