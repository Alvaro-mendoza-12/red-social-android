package conectate.com.presentation.components

import androidx.compose.runtime.Composable
 
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

data class MediaItem(
    val id: String,
    val url: String,
    val type: String
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullScreenImageDialog(
    imageUrl: String? = null,
    mediaItems: List<MediaItem> = emptyList(),
    initialIndex: Int = 0,
    onResolveUrl: (suspend (String) -> String?)? = null,
    onDownload: ((MediaItem) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val currentMediaItem = remember { mutableStateOf<MediaItem?>(null) }
            
            if (mediaItems.isNotEmpty()) {
                val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { mediaItems.size })
                
                // Update current item for download
                LaunchedEffect(pagerState.currentPage) {
                    if (mediaItems.isNotEmpty()) {
                         currentMediaItem.value = mediaItems[pagerState.currentPage]
                    }
                }
                
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val item = mediaItems[page]
                    var resolvedUrl by remember { mutableStateOf(item.url) }
                    
                    LaunchedEffect(item.url) {
                        if (onResolveUrl != null) {
                             // Resolve if it's a relative path OR a Supabase URL (needs signing)
                              if (!item.url.startsWith("http") || item.url.contains("supabase")) {
                                  Log.e("FullScreenImageDialog", "Resolving URL: ${item.url}")
                                  val result = onResolveUrl(item.url)
                                  if (result != null) {
                                      resolvedUrl = result
                                      Log.e("FullScreenImageDialog", "Resolved URL: $resolvedUrl")
                                  } else {
                                      Log.e("FullScreenImageDialog", "FAILED to resolve URL: ${item.url}")
                                      // If resolution fails, we keep the original path which will cause an error in MediaViewerItem
                                  }
                              }
                        }
                    }

                    // Create a resolved copy for the viewer
                    val resolvedItem = item.copy(url = resolvedUrl)
                    
                    val isResolved = resolvedUrl.startsWith("http")
                    if (isResolved || item.type == "image") {
                        MediaViewerItem(item = resolvedItem)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            } else if (imageUrl != null) {
                val singleItem = MediaItem("temp", imageUrl, "image")
                currentMediaItem.value = singleItem
                MediaViewerItem(item = singleItem)
            }

            // Top Bar Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Download Button
                if (onDownload != null && currentMediaItem.value != null) {
                    IconButton(
                        onClick = { onDownload(currentMediaItem.value!!) },
                         modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Descargar",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
                
                // Close Button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaViewerItem(item: MediaItem) {
    if (item.type == "video") {
        val context = LocalContext.current
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var isVideoReady by remember { mutableStateOf(false) }
        var isBuffering by remember { mutableStateOf(true) }
        
        LaunchedEffect(item.url) {
            errorMessage = null
            isVideoReady = false
            isBuffering = true
        }
        
        val exoPlayer = remember(item.url) {
            val okHttpClient = OkHttpClient.Builder().build()
            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(context)
                        .setDataSourceFactory(dataSourceFactory)
                )
                .build().apply {
                val hasExtension = item.url.substringBefore("?").let { it.endsWith(".mp4", true) || it.endsWith(".mkv", true) || it.endsWith(".webm", true) || it.endsWith(".3gp", true) }
                val mediaItem = ExoMediaItem.Builder()
                    .setUri(item.url)
                    .apply {
                        if (item.type == "video" && !hasExtension) {
                            setMimeType(MimeTypes.VIDEO_MP4)
                        } else if (item.type == "audio") {
                            setMimeType(MimeTypes.AUDIO_MPEG)
                        }
                    }
                    .build()
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
                
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                isVideoReady = true
                                isBuffering = false
                            }
                            Player.STATE_BUFFERING -> {
                                isBuffering = true
                            }
                            Player.STATE_ENDED -> {
                                isBuffering = false
                            }
                            else -> Unit
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("FullScreenImageDialog", "ExoPlayer Error: ${error.message}, code=${error.errorCodeName}", error)
                        val errorMsg = "${error.errorCodeName}: ${error.message}"
                        errorMessage = errorMsg
                        isBuffering = false
                        android.widget.Toast.makeText(context, "Debug Error: $errorMsg", android.widget.Toast.LENGTH_LONG).show()
                    }
                })
            }
        }
        
        DisposableEffect(exoPlayer) {
            onDispose {
                exoPlayer.release()
            }
        }
        
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if ((isBuffering || !isVideoReady) && errorMessage == null) {
                CircularProgressIndicator(color = Color.White)
            }
            
            if (errorMessage != null) {
                Column(
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                    Text("Error: $errorMessage", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("URL: ${item.url.take(100)}${if(item.url.length > 100) "..." else ""}", color = Color.Gray, fontSize = 10.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { 
                        errorMessage = null
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                        Text("Reintentar", color = Color.White)
                    }
                }
            }
        }
        
    } else {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var isImageLoading by remember { mutableStateOf(true) }

        AsyncImage(
            model = item.url,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset += pan
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                ),
            contentScale = ContentScale.Fit,
            onLoading = { isImageLoading = true },
            onSuccess = { isImageLoading = false },
            onError = { isImageLoading = false }
        )

        if (isImageLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}
