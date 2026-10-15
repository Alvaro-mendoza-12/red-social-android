package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.Video
import conectate.com.data.model.SharedVideoReference
import conectate.com.data.model.videoWebLink
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

sealed interface SharedVideoPreview {
    data object Loading : SharedVideoPreview
    data class Available(val video: Video) : SharedVideoPreview
    data object Unavailable : SharedVideoPreview
}

@HiltViewModel
class SharedVideoPreviewViewModel @Inject constructor(private val videos: VideoRepository, private val auth: AuthService) : ViewModel() {
    private val requests = Semaphore(4)
    private var owner: String? = auth.getCurrentUserId()
    private val _previews = MutableStateFlow<Map<String, SharedVideoPreview>>(emptyMap())
    val previews = _previews.asStateFlow()
    fun load(id: String, retry: Boolean = false) {
        val account = auth.getCurrentUserId()
        if (owner != account) { owner = account; _previews.value = emptyMap() }
        if (account == null || (!retry && id in _previews.value) || _previews.value[id] == SharedVideoPreview.Loading) return
        _previews.value = (_previews.value + (id to SharedVideoPreview.Loading)).entries.toList().takeLast(40).associate { it.toPair() }
        viewModelScope.launch {
            val result = try { requests.withPermit {
                if (account != owner || account != auth.getCurrentUserId()) null
                else withTimeoutOrNull(8_000) { videos.getVideoById(id).getOrNull() }
            } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (account == owner && account == auth.getCurrentUserId())
                _previews.value = (_previews.value + (id to (result?.let(SharedVideoPreview::Available) ?: SharedVideoPreview.Unavailable)))
                    .entries.toList().takeLast(40).associate { it.toPair() }
        }
    }
}

@Composable
fun SharedVideoMessageCard(reference: SharedVideoReference, onOpenUrl: (String) -> Unit) {
    val model: SharedVideoPreviewViewModel = hiltViewModel()
    val previews by model.previews.collectAsState()
    LaunchedEffect(reference.videoId) { model.load(reference.videoId) }
    SharedVideoCard(previews[reference.videoId] ?: SharedVideoPreview.Loading,
        onOpen = { onOpenUrl(videoWebLink(reference.videoId)) }, onRetry = { model.load(reference.videoId, retry = true) })
}

@Composable
fun SharedVideoCard(preview: SharedVideoPreview, onOpen: () -> Unit, onRetry: () -> Unit = {}) {
    val video = (preview as? SharedVideoPreview.Available)?.video
    Column(Modifier.width(188.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().height(236.dp).background(Color(0xFF18202C))
            .testTag("shared_video_card").clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
            if (!video?.thumbnailUrl.isNullOrBlank()) AsyncImage(
                model = UbuntuStorageUrl.thumbnail(video?.thumbnailUrl, width = 360), contentDescription = "Miniatura del video compartido",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (preview == SharedVideoPreview.Loading) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
            else Icon(Icons.Default.PlayArrow, "Abrir video", tint = Color.White, modifier = Modifier.size(52.dp))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))).padding(12.dp)) {
                Text(video?.userName?.takeIf(String::isNotBlank) ?: "Novvin", color = Color.White,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(video?.title?.takeIf(String::isNotBlank) ?: if (preview == SharedVideoPreview.Unavailable) "Video no disponible" else "Video compartido",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (preview == SharedVideoPreview.Unavailable) TextButton(onClick = onRetry) { Text("Volver a cargar") }
    }
}
