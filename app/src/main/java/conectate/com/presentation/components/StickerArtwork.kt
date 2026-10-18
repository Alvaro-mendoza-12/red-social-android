package conectate.com.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.data.localfallback.UbuntuStorageUrl
import kotlinx.coroutines.delay

/** The same original, animated image is used by chat, recent and favorite stickers. */
@Composable
fun StickerArtwork(
    url: String?,
    description: String = "Sticker",
    modifier: Modifier = Modifier,
    thumbnailUrl: String? = null,
) {
    val context = LocalContext.current
    val original = remember(url) { UbuntuStorageUrl.originalObjectUrl(url) }
    val fallback = remember(thumbnailUrl) { UbuntuStorageUrl.originalObjectUrl(thumbnailUrl) }
    var attempt by remember(original, fallback) { mutableIntStateOf(0) }
    var failed by remember(original, fallback, attempt) { mutableStateOf(false) }
    // Retry one transient failure. A stale thumbnail must not hide a valid sticker.
    val source = if (attempt == 2 && fallback != null) fallback else original ?: fallback
    val request = remember(context, source, attempt) {
        MediaImageRequestFactory.animated(context, source)?.newBuilder()
            ?.setParameter("sticker_retry", attempt)
            ?.build()
    }
    LaunchedEffect(failed, attempt) {
        if (failed && attempt < 1) {
            delay(650)
            attempt += 1
        } else if (failed && attempt == 1 && fallback != null && fallback != original) {
            attempt += 1
        }
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = description,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        onError = { failed = true },
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
        error = {
            Column(
                modifier = Modifier.fillMaxSize().clickable(onClickLabel = "Volver a cargar sticker") { attempt += 1 },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Volver a cargar sticker", modifier = Modifier.size(24.dp))
                Text("Reintentar", style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}
