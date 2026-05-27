package conectate.com.presentation.voicerooms

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

private fun forceCinemaSpeakerOutput(audioManager: AudioManager) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val speaker = audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        if (speaker != null) {
            audioManager.setCommunicationDevice(speaker)
        }
        return
    }
    @Suppress("DEPRECATION")
    run {
        audioManager.isSpeakerphoneOn = true
    }
}

@OptIn(UnstableApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CinemaPlayer(
    videoUrl: String,
    isPlaying: Boolean,
    currentPosition: Long,
    isCreator: Boolean,
    onSync: (Boolean, Long) -> Unit,
    onControl: (String, Long) -> Unit,
    onChangeVideo: () -> Unit,
    viewModel: VoiceRoomActiveViewModel
) {
    val context = LocalContext.current
    var isFullscreen by remember { mutableStateOf(false) }
    var localVolume by remember { mutableStateOf(1f) }


    // v14.0: Audio Attributes - CRITICAL: Use VOICE_COMMUNICATION to mix with WebRTC
    val audioAttributes = remember {
        androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH) // Changed from UNKNOWN
            .setUsage(androidx.media3.common.C.USAGE_VOICE_COMMUNICATION) // CRITICAL: This mixes with mic
            .build()
    }

    val exoPlayer = remember {
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.google.com/"))

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpDataSourceFactory))
            .build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setAudioAttributes(audioAttributes, false) // false = don't handle audio focus
            setHandleAudioBecomingNoisy(false) // Don't pause when headphones disconnect
            volume = 0.8f // Slightly lower to avoid overpowering voices
            setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
        }
    }

    // v15.8: Intelligent Media Engine Selection
    val isDirectStream = videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || 
                       videoUrl.contains(".mkv") || videoUrl.contains("real-debrid.com")

    val isEmbed = remember(videoUrl) { 
        (videoUrl.contains("vidsrc") || videoUrl.contains("embed")) && 
        !isDirectStream
    }

    var isBuffering by remember { mutableStateOf(false) }
    // Monitoring ExoPlayer buffering
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Local Volume Sync
    LaunchedEffect(localVolume) {
        exoPlayer.volume = localVolume
    }

    // Sync from ViewModel (External events)
    LaunchedEffect(viewModel.cinemaSync) {
        viewModel.cinemaSync.collectLatest { event ->
            if (!isCreator && event != null) {
                // More tolerant sync (3 seconds delta) to avoid stutter on slow networks
                if (Math.abs(exoPlayer.currentPosition - event.currentPosition) > 3000) {
                    exoPlayer.seekTo(event.currentPosition)
                }
                if (event.isPlaying != exoPlayer.isPlaying) {
                    if (event.isPlaying) exoPlayer.play() else exoPlayer.pause()
                }
            }
        }
    }


    // v14.0: Track playback errors for automatic fallback
    var hasPlaybackError by remember { mutableStateOf(false) }
    var shouldUseWebViewFallback by remember { mutableStateOf(false) }

    var retryKey by remember { mutableStateOf(0) }
    
    // Load Video with error handling
    LaunchedEffect(videoUrl, retryKey) {
        if (videoUrl.isNotEmpty() && !isEmbed) {
            try {
                Log.d("CinemaPlayer", "🎬 Loading video: $videoUrl")
                
                // Reset state
                hasPlaybackError = false
                shouldUseWebViewFallback = false
                
                val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                try { forceCinemaSpeakerOutput(am) } catch (e: Exception) {}

                delay(300)
                
                val mediaItem = MediaItem.Builder()
                    .setUri(videoUrl)
                    .build()
                
                exoPlayer.setMediaItem(mediaItem)
                // v16.3: Force playback to start when ready
                exoPlayer.playWhenReady = if (isCreator) true else isPlaying
                exoPlayer.prepare()
                
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setPreferredTextLanguage("es") 
                    .build()
                
                // v16.3: Proactive start
                if (!isCreator) {
                    exoPlayer.seekTo(currentPosition)
                    exoPlayer.playWhenReady = isPlaying
                } else {
                    exoPlayer.playWhenReady = true
                }
                
                Log.d("CinemaPlayer", "✅ Video prepared successfully")
            } catch (e: Exception) {
                Log.e("CinemaPlayer", "❌ Error loading video: ${e.message}", e)
                hasPlaybackError = true
                // ONLY fallback to WebView if it's NOT a direct stream (which WebView can't play)
                if (!isDirectStream) {
                    shouldUseWebViewFallback = true
                }
            }
        } else if (isEmbed) {
            exoPlayer.pause()
        }
    }
    
    // Monitor playback events and errors
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("CinemaPlayer", "🚨 Playback error: ${error.errorCodeName} - ${error.message}", error)
                hasPlaybackError = true
                if (!isDirectStream) shouldUseWebViewFallback = true
            }

            override fun onRenderedFirstFrame() {
                Log.i("CinemaPlayer", "📺 FIRST FRAME RENDERED! Video is visible.")
            }

            override fun onPlaybackStateChanged(state: Int) {
                val stateName = when(state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                Log.d("CinemaPlayer", "🔄 ExoPlayer State: $stateName")
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Creator periodically broadcasts sync
    if (isCreator) {
        LaunchedEffect(Unit) {
            while (true) {
                if (exoPlayer.isPlaying) {
                    onSync(true, exoPlayer.currentPosition)
                }
                delay(5000) // v7.2-MVP: Relaxed Interval
            }
        }
    }


    var showControls by remember { mutableStateOf(true) }
    
    // v14.0: Auto-hide controls timer - Faster in fullscreen
    LaunchedEffect(showControls, isFullscreen) {
        if (showControls) {
            val hideDelay = if (isFullscreen) 3000L else 5000L // Más rápido en fullscreen
            delay(hideDelay)
            showControls = false
        }
    }

    // Format time helper
    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }



                       
    val shouldUseWebView = (isEmbed || shouldUseWebViewFallback) // v16.3: Allow fallback for all types

    Box(
        modifier = if (isFullscreen) {
            Modifier.fillMaxSize().background(Color.Black).clickable { showControls = !showControls }
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(16 / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .clickable { showControls = !showControls }
        }
    ) {
        if (shouldUseWebView) {
            var webViewInstance by remember { mutableStateOf<WebView?>(null) }
            
            AndroidView(
                factory = {
                    WebView(it).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (!url.startsWith("http")) return true

                                val blockedKeywords = listOf(
                                    "aliexpress", "shopee", "ads", "pop", "doubleclick",
                                    "onclick", "redirect", "market", "track", "affiliate",
                                    "cpc", "pixel", "analytics", "googleadservices", "vpn",
                                    "bet", "gamble", "18+", "offer", "bonus", "prize"
                                )
                                if (blockedKeywords.any { url.lowercase().contains(it) }) {
                                    Log.d("AdBlock", "Blocked Nav: $url")
                                    return true
                                }
                                return false 
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString()?.lowercase() ?: return null
                                
                                val adDomains = listOf(
                                    "adservice", "googlead", "doubleclick", "adtrack",
                                    "analytics", "popunder", "exoclick", "juicyads",
                                    "popads", "onclickads", "propellerads", "adsterra"
                                )
                                
                                if (adDomains.any { url.contains(it) }) {
                                    Log.d("AdBlock", "Blocked Network Req: $url")
                                    return android.webkit.WebResourceResponse("text/plain", "UTF-8", null)
                                }
                                return null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript("""
                                    (function() {
                                        const killAds = () => {
                                            const video = document.querySelector('video');
                                            
                                            const badSelectors = [
                                                '.ad', '.ads', '.popup', '#pop', '.banner', 
                                                'iframe[src*="ad"]', 'div[id^="div-gpt-ad"]',
                                                '#overlay', '.jw-overlays-container', 
                                                'div[style*="z-index: 2147483647"]',
                                                '.video-ads', '.ytp-ad-overlay-container',
                                                'div[class*="vpn"]', 'div[class*="security"]',
                                                'div[class*="browser"]', 'div[class*="compatible"]',
                                                'div[class*="call"]', 'div[class*="missed"]',
                                                'div[class*="chatmate"]', 'div[id*="chatmate"]',
                                                '[src*="chatmate"]', '.skip-ad', '.btn-skip'
                                            ];
                                            
                                            badSelectors.forEach(sel => {
                                                document.querySelectorAll(sel).forEach(el => {
                                                    if (!video || !el.contains(video)) {
                                                        el.style.setProperty('display', 'none', 'important');
                                                        el.remove();
                                                    }
                                                });
                                            });

                                            if (video) {
                                                video.style.setProperty('display', 'block', 'important');
                                                video.style.setProperty('visibility', 'visible', 'important');
                                                video.style.setProperty('opacity', '1', 'important');
                                                
                                                if (video.paused && !video.ended) video.play().catch(() => {});
                                            }
                                        };

                                        window.open = function() { return null; };

                                        killAds();
                                        setInterval(killAds, 300); 
                                    })();
                                """.trimIndent(), null)
                            }
                        }
                        webChromeClient = WebChromeClient()
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.setSupportMultipleWindows(false) 
                        settings.javaScriptCanOpenWindowsAutomatically = false 
                        
                        loadUrl(videoUrl)
                        webViewInstance = this
                    }
                },
                update = { it.loadUrl(videoUrl) },
                modifier = Modifier.fillMaxSize()
            )
            
            DisposableEffect(Unit) {
                onDispose {
                    webViewInstance?.destroy()
                    webViewInstance = null
                }
            }
            
            // v15.8: Invisible Overlay - ONLY active when controls are shown to prevent background clicks
            if (showControls) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = { showControls = false }
                        )
                )
            }
        } else {
            // v13.0: Force TextureView for better Compose compatibility and Z-order
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        // v16.3: Standard Media3 initialization
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        
                        // Try to enforce TextureView - if reflection fails, SurfaceView will still work
                        // but we ensure it's on top to avoid black screen issues with Compose
                        try {
                            val surfaceTypeField = PlayerView::class.java.getDeclaredField("surfaceType")
                            surfaceTypeField.isAccessible = true
                            surfaceTypeField.setInt(this, 2) // SURFACE_TYPE_TEXTURE_VIEW
                        } catch (e: Exception) {
                            // If reflection fails, ensure SurfaceView doesn't stay behind the background
                            // using a robust search since property names vary between Media3 versions
                            post {
                                for (i in 0 until childCount) {
                                    val child = getChildAt(i)
                                    if (child is android.view.SurfaceView) {
                                        child.setZOrderMediaOverlay(true)
                                        break
                                    }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Processing / Buffering Indicator - v13.0: Improved logic
        if (isBuffering || (!isEmbed && exoPlayer.playbackState == Player.STATE_BUFFERING)) {
             Box(modifier = Modifier.fillMaxSize().zIndex(5f), contentAlignment = Alignment.Center) {
                 CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                 Text(
                     "Cargando video...", 
                     modifier = Modifier.padding(top = 64.dp),
                     color = Color.White,
                     fontSize = 12.sp
                 )
             }
        }

        // Premium Overlay (Animated)
        AnimatedVisibility(
            visible = showControls,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.zIndex(10f) // CRITICAL: Always on TOP
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(0.6f), Color.Transparent, Color.Black.copy(0.6f))))
                    .padding(16.dp)
            ) {
                // TOP BAR: Title and Close
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            text = viewModel.cinemaState.value?.videoTitle ?: "Cine Premium",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isCreator) "Sincronizado • Tú eres el Host" else "Sincronizado • Escuchando...",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Spacer(Modifier.width(8.dp))
                            // v14.0: Manual Fallback Buttons
                            Row {
                                if (videoUrl.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = { retryKey++ },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(24.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Recargar", fontSize = 10.sp)
                                    }
                                    
                                    Spacer(Modifier.width(8.dp))
                                    
                                    if (!shouldUseWebViewFallback) {
                                        OutlinedButton(
                                            onClick = { shouldUseWebViewFallback = true },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            modifier = Modifier.height(24.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.5f)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                        ) {
                                            Text("Abrir en Web", fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }


                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // v15.0: Botón Cambiar Video
                        IconButton(
                            onClick = { onChangeVideo() },
                            modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VideoLibrary,
                                contentDescription = "Cambiar Video",
                                tint = Color.White
                            )
                        }
                        
                        Spacer(Modifier.width(8.dp))
                        
                         IconButton(
                            onClick = { isFullscreen = !isFullscreen },
                            modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)
                         ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Fullscreen",
                                tint = Color.White
                            )
                         }
                         
                         Spacer(Modifier.width(8.dp))
                         
                         if (isCreator) {
                            Button(
                                onClick = { viewModel.exitCinemaMode() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Cerrar", fontSize = 12.sp)
                            }
                         }
                    }
                }

                // CENTER: Large Play/Pause and Skip Buttons (v11.0)
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    IconButton(onClick = { 
                        if (isCreator) {
                            val target = (exoPlayer.currentPosition - 10000).coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                            onControl("seek", target)
                        }
                    }) {
                        Icon(Icons.Default.Replay10, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                    }

                    IconButton(onClick = { 
                        if (isCreator) {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            onControl(if (exoPlayer.isPlaying) "pause" else "play", exoPlayer.currentPosition)
                        }
                    }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(82.dp)
                        )
                    }

                    IconButton(onClick = { 
                        if (isCreator) {
                            val target = (exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration)
                            exoPlayer.seekTo(target)
                            onControl("seek", target)
                        }
                    }) {
                        Icon(Icons.Default.Forward10, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }

                // BOTTOM: Seeker and Volume
                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(exoPlayer.currentPosition), color = Color.White, fontSize = 11.sp)
                        Text(formatTime(exoPlayer.duration), color = Color.White, fontSize = 11.sp)
                    }
                    
                    Slider(
                        value = exoPlayer.currentPosition.toFloat(),
                        onValueChange = { if (isCreator) exoPlayer.seekTo(it.toLong()) },
                        onValueChangeFinished = { 
                            if (isCreator) onControl("seek", exoPlayer.currentPosition)
                        },
                        valueRange = 0f..exoPlayer.duration.coerceAtLeast(1L).toFloat(),
                        enabled = isCreator,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Slider(
                            value = localVolume,
                            onValueChange = { localVolume = it },
                            modifier = Modifier.width(80.dp),
                            valueRange = 0f..1f
                        )
                    }
                }
            }
        }
    }

    // Auto-rotate and Hide System Bars for Fullscreen
    LaunchedEffect(isFullscreen) {
        viewModel.setPlayerFullscreen(isFullscreen) // Notificar al ViewModel para ocultar bottomBar
        
        val activity = context as? android.app.Activity
        val window = activity?.window
        val controller = if (window != null) androidx.core.view.WindowCompat.getInsetsController(window, window.decorView) else null
        
        if (isFullscreen) {
             activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
             // v14.0: Aggressive Immersive Mode
             controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
             controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
             activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
             controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.setPlayerFullscreen(false) // Reset on exit
            val activity = context as? android.app.Activity
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            exoPlayer.release()
        }
    }
}
