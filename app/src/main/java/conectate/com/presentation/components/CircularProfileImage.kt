package conectate.com.presentation.components

import android.os.Build
import android.content.SharedPreferences
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import conectate.com.data.localfallback.UbuntuStorageUrl
import java.text.Normalizer
import java.util.Locale

@Composable
fun CircularProfileImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    showBorder: Boolean = true,
    borderColor: Color = MaterialTheme.colorScheme.primary,
    borderWidth: Dp = 2.dp,
    isVerified: Boolean = false,
    mediaType: String? = null,
    muted: Boolean = true,
    allowSound: Boolean = false,
    fallbackName: String? = null
) {
    val context = LocalContext.current
    val resolvedImageUrl = remember(imageUrl, UbuntuStorageUrl.baseUrl) {
        UbuntuStorageUrl.rewrite(imageUrl)
    }
    val profileAvatarMutedKey = "profile_avatar_videos_muted"
    val prefs = remember(context) {
        context.getSharedPreferences("theme_prefs", android.content.Context.MODE_PRIVATE)
    }
    val globalAnimatedAvatarsMuted = remember(prefs) {
        mutableStateOf(prefs.getBoolean(profileAvatarMutedKey, true))
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == profileAvatarMutedKey) {
                globalAnimatedAvatarsMuted.value = sharedPreferences.getBoolean(profileAvatarMutedKey, true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val isVideoAvatar = remember(mediaType, resolvedImageUrl) {
        val cleanType = mediaType?.trim()?.lowercase().orEmpty()
        val cleanUrl = resolvedImageUrl?.substringBefore("?")?.lowercase().orEmpty()
        cleanType == "video" ||
            cleanType.startsWith("video/") ||
            cleanUrl.endsWith(".mp4") ||
            cleanUrl.endsWith(".m4v") ||
            cleanUrl.endsWith(".mov")
    }
    val effectiveMuted = muted || globalAnimatedAvatarsMuted.value || !allowSound
    val fallbackInitial = remember(fallbackName) {
        avatarFallbackInitial(fallbackName)
    }
    val request = remember(resolvedImageUrl) {
        resolvedImageUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCacheKey(UbuntuStorageUrl.stableCacheKey(imageUrl))
                .diskCacheKey(UbuntuStorageUrl.stableCacheKey(imageUrl))
                .crossfade(false)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    shape = CircleShape
                    clip = true
                }
                .clip(CircleShape)
                .then(
                    if (showBorder) {
                        Modifier.border(
                            BorderStroke(borderWidth, borderColor),
                            shape = CircleShape
                        )
                    } else {
                        Modifier
                    }
                )
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (resolvedImageUrl.isNullOrEmpty()) {
                AvatarFallback(
                    initial = fallbackInitial,
                    size = size
                )
            } else if (isVideoAvatar) {
                val exoPlayer = remember(resolvedImageUrl) {
                    ExoPlayer.Builder(context).build().apply {
                        repeatMode = Player.REPEAT_MODE_ONE
                        volume = 0f
                        playWhenReady = false
                        setMediaItem(MediaItem.fromUri(resolvedImageUrl))
                        prepare()
                    }
                }
                LaunchedEffect(effectiveMuted, exoPlayer) {
                    exoPlayer.volume = if (effectiveMuted) 0f else 1f
                    exoPlayer.playWhenReady = true
                }
                val textureViewRef = remember(resolvedImageUrl) { mutableStateOf<TextureView?>(null) }
                DisposableEffect(exoPlayer) {
                    onDispose {
                        textureViewRef.value?.let { exoPlayer.clearVideoTextureView(it) }
                        exoPlayer.release()
                    }
                }
                AndroidView(
                    factory = { viewContext ->
                        TextureView(viewContext).apply {
                            isOpaque = false
                            textureViewRef.value = this
                            exoPlayer.setVideoTextureView(this)
                        }
                    },
                    update = { textureView ->
                        textureViewRef.value = textureView
                        exoPlayer.setVideoTextureView(textureView)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            shape = CircleShape
                            clip = true
                        }
                        .clip(CircleShape)
                )
            } else {
                // Load image with Coil
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = "Profile Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    loading = {
                        AvatarFallback(initial = fallbackInitial, size = size)
                    },
                    error = {
                        AvatarFallback(initial = fallbackInitial, size = size)
                    }
                )
            }
        }
        
        if (isVerified) {
            Box(
                modifier = Modifier
                    .size(size * 0.32f)
                    .align(Alignment.BottomEnd)
                    .background(Color.White, CircleShape)
                    .padding(1.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Verificado",
                    modifier = Modifier.fillMaxSize(),
                    tint = Color(0xFF1DA1F2)
                )
            }
        }
    }
}

@Composable
private fun AvatarFallback(initial: String, size: Dp) {
    if (initial == "?") {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Avatar sin foto",
            modifier = Modifier.size(size * 0.58f),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    } else {
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

internal fun avatarFallbackInitial(name: String?): String {
    val clean = name
        ?.trim()
        ?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
        ?: return "?"
    val firstLetter = clean.firstOrNull { it.isLetterOrDigit() } ?: return "?"
    val normalized = Normalizer.normalize(firstLetter.toString(), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .uppercase(Locale.ROOT)
    return normalized.firstOrNull()?.toString() ?: "?"
}
