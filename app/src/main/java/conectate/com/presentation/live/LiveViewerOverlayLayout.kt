package conectate.com.presentation.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.dp

/** Measure the composer, including system insets, before placing sibling controls. */
@Composable
internal fun LiveViewerOverlayLayout(
    metrics: LiveResponsiveMetrics,
    modifier: Modifier = Modifier,
    chat: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    composer: @Composable () -> Unit,
    onBottomAreaMeasured: (Int) -> Unit = {},
) {
    val scroll = rememberScrollState()
    SubcomposeLayout(modifier.fillMaxSize().imePadding()) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val edge = metrics.horizontalPadding.roundToPx()
        val gap = 12.dp.roundToPx()
        val topClearance = 96.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bottom = subcompose("composer") {
            Box(Modifier.fillMaxWidth()) { composer() }
        }.single().measure(loose.copy(minWidth = width))
        val controlsHeight = (height - bottom.height - gap - topClearance).coerceAtLeast(0)
        val controlsWidth = (width - edge * 2).coerceAtLeast(0)
        val controls = subcompose("actions") {
            Box(if (metrics.horizontalActions) Modifier.fillMaxWidth() else Modifier.verticalScroll(scroll)) {
                actions()
            }
        }.single().measure(loose.copy(maxWidth = controlsWidth, maxHeight = controlsHeight))
        val chatBottom = bottom.height + gap +
            if (metrics.horizontalActions) controls.height + gap else 0
        val conversation = subcompose("chat") { Box { chat() } }.single().measure(
            loose.copy(
                maxWidth = (width * .72f).toInt(),
                maxHeight = (height - chatBottom - topClearance).coerceAtLeast(0),
            )
        )
        onBottomAreaMeasured(chatBottom + conversation.height)
        layout(width, height) {
            conversation.placeRelative(edge, (height - chatBottom - conversation.height).coerceAtLeast(0))
            controls.placeRelative(
                if (metrics.horizontalActions) edge else (width - edge - controls.width).coerceAtLeast(0),
                (height - bottom.height - gap - controls.height).coerceAtLeast(0),
            )
            bottom.placeRelative(0, (height - bottom.height).coerceAtLeast(0))
        }
    }
}
