package conectate.com.presentation.wallet

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import conectate.com.R
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.visualKey
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Secuencia de poses dibujadas: no deforma ni traslada una miniatura estática.
 * Cada celda contiene una postura distinta del personaje.
 */
internal data class GiftSpriteSheetSpec(
    @DrawableRes val drawableRes: Int,
    val columns: Int = 4,
    val rows: Int = 2,
    val frameCount: Int = 8,
    val cycles: Float
)

internal fun giftSpriteSheetSpec(visualKey: String): GiftSpriteSheetSpec? = when (visualKey) {
    "solar-hummingbird" -> GiftSpriteSheetSpec(
        drawableRes = R.drawable.gift_anim_solar_hummingbird_sheet,
        cycles = 5.5f
    )
    "forest-guardian" -> GiftSpriteSheetSpec(
        drawableRes = R.drawable.gift_anim_forest_guardian_sheet,
        cycles = 2.25f
    )
    "cosmic-whale" -> GiftSpriteSheetSpec(
        drawableRes = R.drawable.gift_anim_cosmic_whale_sheet,
        cycles = 2f
    )
    "celestial-dragon" -> GiftSpriteSheetSpec(
        drawableRes = R.drawable.gift_anim_celestial_dragon_sheet,
        cycles = 1.75f
    )
    else -> null
}

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

    val spriteSheet = ImageBitmap.imageResource(spec.drawableRes)
    val frame = giftSpriteFrame(progress, animationsEnabled, spec)
    val frameWidth = remember(spriteSheet.width, spec.columns) {
        spriteSheet.width / spec.columns.coerceAtLeast(1)
    }
    val frameHeight = remember(spriteSheet.height, spec.rows) {
        spriteSheet.height / spec.rows.coerceAtLeast(1)
    }
    val sourceColumn = frame % spec.columns
    val sourceRow = frame / spec.columns

    Canvas(modifier = modifier) {
        drawImage(
            image = spriteSheet,
            srcOffset = IntOffset(sourceColumn * frameWidth, sourceRow * frameHeight),
            srcSize = IntSize(frameWidth, frameHeight),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.High
        )
    }
}
