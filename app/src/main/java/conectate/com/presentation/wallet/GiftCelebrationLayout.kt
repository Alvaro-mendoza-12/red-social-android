package conectate.com.presentation.wallet

import kotlin.math.min

/**
 * Valores de insets expresados en dp para que el cálculo sea comprobable sin
 * depender de Compose ni de una ventana Android concreta.
 */
internal data class GiftViewportInsetsDp(
    val startDp: Float = 0f,
    val topDp: Float = 0f,
    val endDp: Float = 0f,
    val bottomDp: Float = 0f
)

/**
 * Zona segura para la celebración. El fondo puede ocupar toda la pantalla,
 * pero el arte y el texto nunca atraviesan barras del sistema ni el compositor
 * del directo.
 */
internal data class GiftCelebrationLayout(
    val safeWidthDp: Float,
    val safeHeightDp: Float,
    val artworkAreaHeightDp: Float,
    val artworkSizeDp: Float,
    val artworkTranslationXDp: Float,
    val artworkTranslationYDp: Float,
    val bottomControlsClearanceDp: Float,
    val captionBottomPaddingDp: Float,
    val captionMaxWidthDp: Float,
    val compactTypography: Boolean,
    val landscape: Boolean
)

/**
 * Limita la pieza protagonista a la región utilizable de la pantalla. Las
 * trayectorias semánticas pueden pedir entrar por un lateral, pero se recortan
 * al borde seguro: así siguen comunicando movimiento sin cubrir la navegación,
 * la cámara perforada, el teclado o el cuadro de comentario.
 */
internal fun calculateGiftCelebrationLayout(
    viewportWidthDp: Float,
    viewportHeightDp: Float,
    insets: GiftViewportInsetsDp,
    baseArtworkSizeDp: Float,
    artworkScale: Float,
    motion: GiftMotionTransform,
    preview: Boolean,
    showCaption: Boolean
): GiftCelebrationLayout {
    val viewportWidth = viewportWidthDp.coerceAtLeast(0f)
    val viewportHeight = viewportHeightDp.coerceAtLeast(0f)
    val start = insets.startDp.coerceIn(0f, viewportWidth)
    val end = insets.endDp.coerceIn(0f, (viewportWidth - start).coerceAtLeast(0f))
    val top = insets.topDp.coerceIn(0f, viewportHeight)
    val bottom = insets.bottomDp.coerceIn(0f, (viewportHeight - top).coerceAtLeast(0f))
    val safeWidth = (viewportWidth - start - end).coerceAtLeast(0f)
    val safeHeight = (viewportHeight - top - bottom).coerceAtLeast(0f)
    val landscape = safeWidth > safeHeight
    val compact = safeWidth < 360f || safeHeight < 520f || landscape

    // El compositor del live queda siempre por encima de esta banda. La vista
    // previa no tiene compositor y usa todo el cuadro del diálogo.
    val requestedBottomClearance = when {
        preview || !showCaption -> 0f
        landscape -> 72f
        else -> 112f
    }
    // Cuando el teclado deja una franja muy baja, conservar 112dp haría que
    // el texto desaparezca. La reserva escala hacia abajo sin invadir el
    // compositor ni los insets que ya se descontaron de safeHeight.
    val bottomClearance = min(requestedBottomClearance, safeHeight * 0.35f)
    val artworkAreaHeight = (safeHeight - bottomClearance).coerceAtLeast(0f)
    val requestedArtworkSize = (baseArtworkSizeDp.coerceAtLeast(0f) * artworkScale.coerceAtLeast(0f))
    val widthCap = safeWidth * if (preview) 0.88f else if (landscape) 0.42f else 0.74f
    val heightCap = artworkAreaHeight * if (preview) 0.82f else 0.78f
    val maxArtworkSize = min(widthCap, heightCap).coerceAtLeast(0f)
    val preferredMinimum = if (preview) 72f else if (landscape) 68f else 72f
    val artworkSize = when {
        maxArtworkSize <= 0f -> 0f
        maxArtworkSize < preferredMinimum -> maxArtworkSize
        else -> requestedArtworkSize.coerceIn(preferredMinimum, maxArtworkSize)
    }

    // El origen del Box seguro es su centro. Reservar una banda inferior mueve
    // ese origen hacia arriba y deja sitio para comentario, regalar y enviar.
    val maxHorizontalTravel = ((safeWidth - artworkSize) / 2f).coerceAtLeast(0f)
    val maxVerticalTravel = ((artworkAreaHeight - artworkSize) / 2f).coerceAtLeast(0f)
    val centeredAboveControls = if (bottomClearance > 0f) -bottomClearance / 2f else 0f
    val requestedX = motion.translationX * safeWidth * 0.5f
    val requestedY = centeredAboveControls + motion.translationY * artworkAreaHeight * 0.5f
    val translationX = requestedX.coerceIn(-maxHorizontalTravel, maxHorizontalTravel)
    val translationY = requestedY.coerceIn(
        centeredAboveControls - maxVerticalTravel,
        centeredAboveControls + maxVerticalTravel
    )

    return GiftCelebrationLayout(
        safeWidthDp = safeWidth,
        safeHeightDp = safeHeight,
        artworkAreaHeightDp = artworkAreaHeight,
        artworkSizeDp = artworkSize,
        artworkTranslationXDp = translationX,
        artworkTranslationYDp = translationY,
        bottomControlsClearanceDp = bottomClearance,
        captionBottomPaddingDp = if (preview) 12f else bottomClearance,
        captionMaxWidthDp = (safeWidth * 0.92f).coerceAtMost(if (landscape) 420f else 460f),
        compactTypography = compact,
        landscape = landscape
    )
}
