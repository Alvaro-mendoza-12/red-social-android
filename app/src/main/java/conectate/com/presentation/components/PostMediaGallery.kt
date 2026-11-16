package conectate.com.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Photo proportions belong to each source, including mixed-format carousel pages. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PostMediaGallery(urls: List<String>, viewportHeight: Dp, onOpen: (Int) -> Unit) {
    if (urls.isEmpty()) return
    val ratios = remember(urls) { mutableStateMapOf<String, Float>() }
    val pager = rememberPagerState(pageCount = { urls.size })
    val selected = pager.settledPage.coerceIn(urls.indices)
    val selectedUrl = urls[selected]
    val aspectRatio = ratios[selectedUrl] ?: (4f / 3f)
    val photoHeightLimit = (viewportHeight * .72f).coerceIn(160.dp, 760.dp)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = if (postMediaType(selectedUrl) == "video") 300.dp.coerceAtMost(photoHeightLimit)
            else (maxWidth / aspectRatio).coerceIn(120.dp, photoHeightLimit)
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(height).testTag("post_media_stage")) {
                if (urls.size == 1) {
                    PostMediaTile(urls[0], Modifier.fillMaxSize(), onAspectRatio = { ratios[urls[0]] = it }) { onOpen(0) }
                } else {
                    HorizontalPager(pager, modifier = Modifier.fillMaxSize()) { page ->
                        val url = urls[page]
                        PostMediaTile(url, Modifier.fillMaxSize(), onAspectRatio = { ratios[url] = it }) { onOpen(page) }
                    }
                }
            }
            // Keep pagination outside the photograph so its bottom edge remains visible.
            if (urls.size > 1) Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(urls.size) { index ->
                    Box(Modifier.padding(horizontal = 2.dp).size(6.dp).clip(CircleShape)
                        .background(if (pager.currentPage == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)))
                }
            }
        }
    }
}
