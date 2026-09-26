package conectate.com.presentation.components

import android.annotation.SuppressLint
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Obtiene una separación inferior fiable incluso dentro de diálogos de pantalla
 * completa. Algunos fabricantes no propagan los insets de gestos a Compose en
 * esos diálogos, por lo que se conserva una reserva mínima y se usa la dimensión
 * de Android como último respaldo.
 */
@SuppressLint("DiscouragedApi", "InternalInsetResource")
@Composable
fun rememberReliableBottomSafePadding(
    minimum: Dp = 28.dp,
    contentClearance: Dp = 0.dp,
): Dp {
    val resources = LocalResources.current
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    fun resolveBottomInsetPx(): Int {
        val insets = ViewCompat.getRootWindowInsets(view)
        val systemBottom = insets
            ?.getInsets(
                WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.mandatorySystemGestures()
            )
            ?.bottom
            ?: 0
        val resourceId = resources.getIdentifier(
            "navigation_bar_height",
            "dimen",
            "android"
        )
        val resourceBottom = if (resourceId != 0) {
            runCatching { resources.getDimensionPixelSize(resourceId) }.getOrDefault(0)
        } else {
            0
        }
        return max(systemBottom, resourceBottom)
    }

    var bottomInsetPx by remember(view, configuration, resources) {
        mutableIntStateOf(resolveBottomInsetPx())
    }

    DisposableEffect(view, configuration, resources) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            bottomInsetPx = resolveBottomInsetPx()
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        view.requestApplyInsets()
        bottomInsetPx = resolveBottomInsetPx()
        onDispose {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
    }

    val measured = with(density) { bottomInsetPx.toDp() }
    return reliableBottomSafePadding(measured, minimum, contentClearance)
}

/**
 * Reserva el inset real y, cuando la pantalla lo necesita, una separación visual
 * adicional. La separación se suma al inset medido en lugar de depender de la
 * altura del teléfono, por lo que funciona igual con gestos y navegación de tres
 * botones sin introducir offsets específicos por modelo.
 */
internal fun reliableBottomSafePadding(
    measuredInset: Dp,
    minimum: Dp,
    contentClearance: Dp,
): Dp = maxOf(minimum, measuredInset.coerceAtLeast(0.dp)) + contentClearance.coerceAtLeast(0.dp)

/**
 * En ventanas especialmente altas la franja de gestos queda visualmente más
 * próxima a la última línea del post aunque el inset técnico sea idéntico. Esta
 * compensación depende del viewport, no de la marca o el modelo del teléfono.
 */
internal fun tallPostOverlayClearance(screenHeightDp: Int): Dp = when {
    screenHeightDp >= 860 -> 24.dp
    screenHeightDp >= 820 -> 12.dp
    else -> 0.dp
}
