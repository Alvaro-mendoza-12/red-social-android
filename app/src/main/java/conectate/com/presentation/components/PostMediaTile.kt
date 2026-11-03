package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import conectate.com.core.media.MediaImageRequestFactory

private enum class PostImageStatus { Loading, Ready, Failed }

internal fun postMediaType(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase(java.util.Locale.ROOT)
    return if (listOf(".mp4", ".webm", ".mov", ".m4v", ".mkv", ".m3u8").any(path::endsWith)) "video" else "image"
}

/** A list shows a play control; the existing full-screen viewer owns playback. */
@Composable
internal fun PostMediaTile(url: String, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    if (postMediaType(url) == "video") {
        Surface(onClick = onOpen, modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.PlayCircle, "Reproducir video de la publicación", Modifier.size(64.dp))
                Spacer(Modifier.height(8.dp))
                Text("Reproducir video", style = MaterialTheme.typography.labelLarge)
            }
        }
    } else {
        val context = LocalContext.current
        val request = remember(context, url) { MediaImageRequestFactory.preview(context, url) }
        var status by remember(request) { mutableStateOf(PostImageStatus.Loading) }
        Box(
            modifier.inlinePhotoZoom(url).clickable(onClick = onOpen)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = request,
                contentDescription = if (status == PostImageStatus.Failed) null else "Imagen de publicación",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onLoading = { status = PostImageStatus.Loading },
                onSuccess = { status = PostImageStatus.Ready },
                onError = { status = PostImageStatus.Failed },
            )
            when (status) {
                PostImageStatus.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                PostImageStatus.Ready -> Unit
                PostImageStatus.Failed -> Icon(
                    Icons.Default.BrokenImage, "No se pudo cargar la imagen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
