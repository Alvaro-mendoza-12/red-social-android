package conectate.com.presentation.components

import android.content.SharedPreferences
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.core.media.ProfileIdentityImageStore
import conectate.com.core.media.isVideoProfileAvatar
import conectate.com.core.media.profileAvatarImageRequest
import conectate.com.core.media.profileAvatarPosterRequest
import conectate.com.core.media.profileIdentityPreviewDimensions
import conectate.com.data.localfallback.UbuntuStorageUrl
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@Composable
fun CircularProfileImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    showBorder: Boolean = false,
    borderColor: Color = MaterialTheme.colorScheme.primary,
    borderWidth: Dp = 2.dp,
    isVerified: Boolean = false,
    mediaType: String? = null,
    muted: Boolean = true,
    allowSound: Boolean = false,
    fallbackName: String? = null,
    momentViewed: Boolean? = null,
    ownerId: String? = null,
    openMomentOnClick: Boolean = true,
    showMomentRing: Boolean = true
) {
    val context = LocalContext.current
    val windowSize = LocalWindowInfo.current.containerSize
    val identityAccountId = LocalProfileIdentityAccount.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleStarted = remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.STARTED
            )
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            lifecycleStarted.value = lifecycleOwner.lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.STARTED
            )
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
        isVideoProfileAvatar(resolvedImageUrl, mediaType)
    }
    val effectiveMuted = muted || globalAnimatedAvatarsMuted.value || !allowSound
    // El avatar de video forma parte de la identidad del perfil: debe animarse en
    // cualquier dispositivo mientras la pantalla esté visible. LazyColumn/LazyRow
    // ya eliminan los elementos fuera de pantalla y DisposableEffect libera el
    // reproductor al salir, por lo que no necesitamos degradarlo a una miniatura
    // estática en equipos que el detector clasifica como gama baja.
    val visibleOnScreen = remember(resolvedImageUrl) { mutableStateOf(false) }
    val shouldAnimateVideoAvatar = isVideoAvatar && lifecycleStarted.value && visibleOnScreen.value
    val fallbackInitial = remember(fallbackName) {
        avatarFallbackInitial(fallbackName)
    }
    val avatarDisplayUrl = remember(resolvedImageUrl, isVideoAvatar) {
        if (isVideoAvatar) resolvedImageUrl else MediaImageRequestFactory.avatarUrl(resolvedImageUrl)
    }
    val cacheKey = remember(avatarDisplayUrl) { UbuntuStorageUrl.stableCacheKey(avatarDisplayUrl) }
    val request = remember(context, resolvedImageUrl, mediaType, identityAccountId) {
        profileAvatarImageRequest(context, resolvedImageUrl, mediaType)
    }
    val posterRequest = remember(context, resolvedImageUrl, mediaType, identityAccountId) {
        profileAvatarPosterRequest(context, resolvedImageUrl, mediaType)
    }
    val cachedIdentityImage = remember(resolvedImageUrl, identityAccountId) {
        mutableStateOf(ProfileIdentityImageStore.peek(resolvedImageUrl, identityAccountId))
    }
    val cachedIdentityPreview = remember(resolvedImageUrl, identityAccountId) {
        mutableStateOf(ProfileIdentityImageStore.peekPreview(resolvedImageUrl, identityAccountId))
    }
    val identityGeneration = remember(resolvedImageUrl, identityAccountId) { ProfileIdentityImageStore.generation(identityAccountId) }
    LaunchedEffect(resolvedImageUrl, identityAccountId) {
        if (cachedIdentityImage.value != null && cachedIdentityPreview.value != null) return@LaunchedEffect
        cachedIdentityImage.value = ProfileIdentityImageStore.find(context, resolvedImageUrl, identityAccountId, identityGeneration)
        cachedIdentityPreview.value = ProfileIdentityImageStore.peekPreview(resolvedImageUrl, identityAccountId)
    }
    val momentAccess = LocalAvatarMoments.current
    val momentOwner = remember(ownerId, resolvedImageUrl) { avatarMomentOwnerId(ownerId, resolvedImageUrl) }
    DisposableEffect(momentOwner, momentAccess?.owner, identityAccountId, visibleOnScreen.value, showMomentRing) {
        val registration = if (showMomentRing && momentOwner != null && visibleOnScreen.value) momentAccess?.owner?.register(momentOwner) else null
        onDispose { if (registration != null) momentAccess?.owner?.unregister(registration) }
    }
    val activeMoment = momentOwner?.let { momentAccess?.moments?.get(it) }
    val resolvedMomentViewed = if (showMomentRing) momentViewed ?: activeMoment?.isViewed else null
    val ring = if (resolvedMomentViewed == null) Modifier else Modifier
        .border(
            2.dp,
            if (resolvedMomentViewed) androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline)
            else androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color(0xFF08CCC9), Color(0xFF129EE7), Color(0xFF6546D7), Color(0xFF08CCC9))),
            CircleShape,
        ).padding(4.dp).semantics { stateDescription = if (resolvedMomentViewed) "Momento visto" else "Momento sin ver" }
    val openMoment = if (showMomentRing && openMomentOnClick && momentViewed == null && activeMoment != null && momentAccess != null) {
        Modifier.clickable { momentAccess.open(activeMoment) }
    } else Modifier
    Box(modifier = modifier.size(size).then(ring).then(openMoment).onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visibleOnScreen.value = profileAvatarIsVisible(bounds, windowSize)
    }) {
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
            } else if (shouldAnimateVideoAvatar) {
                val exoPlayerState = remember(resolvedImageUrl, identityAccountId) { mutableStateOf<ExoPlayer?>(null) }
                val playerReleased = remember(resolvedImageUrl) { AtomicBoolean(false) }
                val firstFrameRendered = remember(resolvedImageUrl) { mutableStateOf(false) }
                val textureViewRef = remember(resolvedImageUrl) { mutableStateOf<TextureView?>(null) }
                SavedProfileIdentityImage(
                    posterFile = cachedIdentityImage.value,
                    preview = cachedIdentityPreview.value,
                )
                if (!firstFrameRendered.value && cachedIdentityPreview.value == null && posterRequest != null) {
                    AsyncImage(
                        model = posterRequest,
                        contentDescription = "Foto del perfil",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onSuccess = { state ->
                            cachedIdentityPreview.value = ProfileIdentityImageStore.retainDrawable(
                                context, resolvedImageUrl, state.result.drawable, identityAccountId, identityGeneration,
                            )
                        },
                    )
                }
                DisposableEffect(resolvedImageUrl, identityAccountId) {
                    playerReleased.set(false)
                    firstFrameRendered.value = false
                    val player = ExoPlayer.Builder(context)
                        .setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(context)
                            .setEnableDecoderFallback(true))
                        .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                            .setBufferDurationsMs(4_000, 8_000, 150, 1_000)
                            .setPrioritizeTimeOverSizeThresholds(true).build())
                        .setMediaSourceFactory(
                            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                                conectate.com.util.VideoCache.getCacheDataSourceFactory(context),
                            ),
                        )
                        .build().apply {
                        repeatMode = Player.REPEAT_MODE_ONE
                        volume = 0f
                        trackSelectionParameters = trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, effectiveMuted)
                            .build()
                        playWhenReady = true
                    }
                    val listener = object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            firstFrameRendered.value = true
                            if (conectate.com.BuildConfig.DEBUG) android.util.Log.d("AvatarPlaybackAudit", "first_frame identity=${cacheKey?.hashCode()}")
                        }
                    }
                    player.addListener(listener)
                    player.setMediaItem(MediaItem.fromUri(resolvedImageUrl))
                    player.prepare()
                    exoPlayerState.value = player
                    onDispose {
                        playerReleased.set(true)
                        exoPlayerState.value = null
                        player.removeListener(listener)
                        player.stop()
                        player.release()
                    }
                }
                val exoPlayer = exoPlayerState.value
                if (exoPlayer != null) {
                    // Navigation creates this player while the entry is STARTED.
                    // The ordinary background guard pauses it until RESUMED;
                    // avatars must then restore their automatic playback intent.
                    PauseMediaPlayerOnBackground(exoPlayer, resumeOnReturn = true)
                    LaunchedEffect(effectiveMuted, exoPlayer) {
                        if (!playerReleased.get()) {
                            exoPlayer.volume = if (effectiveMuted) 0f else 1f
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, effectiveMuted)
                                .build()
                            exoPlayer.playWhenReady = true
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
                            if (!playerReleased.get() && textureViewRef.value !== textureView) {
                                textureViewRef.value = textureView
                                exoPlayer.setVideoTextureView(textureView)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (firstFrameRendered.value) 1f else 0f)
                            .graphicsLayer {
                                shape = CircleShape
                                clip = true
                            }
                            .clip(CircleShape)
                    )
                }
                LaunchedEffect(firstFrameRendered.value, resolvedImageUrl, identityAccountId) {
                    if (!firstFrameRendered.value || resolvedImageUrl.isNullOrBlank() || cachedIdentityPreview.value != null) {
                        return@LaunchedEffect
                    }
                    // The callback can happen before TextureView has published the
                    // frame. A short UI-only delay avoids reading an empty bitmap.
                    delay(80L)
                    val texture = textureViewRef.value ?: return@LaunchedEffect
                    val dimensions = profileIdentityPreviewDimensions(texture.width, texture.height)
                    val bitmap = runCatching { texture.getBitmap(dimensions.first, dimensions.second) }.getOrNull()
                        ?: return@LaunchedEffect
                    cachedIdentityPreview.value = ProfileIdentityImageStore.retainBitmap(
                        context, resolvedImageUrl, bitmap, identityAccountId, identityGeneration,
                    )
                }
            } else if (isVideoAvatar) {
                SavedProfileIdentityImage(
                    posterFile = cachedIdentityImage.value,
                    preview = cachedIdentityPreview.value,
                )
            } else {
                // Draw decoded identity pixels synchronously underneath refreshes.
                // A known photo never becomes an initial while Coil is loading.
                SavedProfileIdentityImage(cachedIdentityImage.value, cachedIdentityPreview.value)
                AsyncImage(
                    model = request ?: avatarDisplayUrl,
                    contentDescription = "Profile Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = { state ->
                        if (cachedIdentityPreview.value != null) return@AsyncImage
                        cachedIdentityPreview.value = ProfileIdentityImageStore.retainDrawable(
                            context, resolvedImageUrl, state.result.drawable, identityAccountId, identityGeneration,
                        )
                    },
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

internal fun profileAvatarIsVisible(bounds: Rect, viewport: IntSize): Boolean =
    bounds.width > 0f && bounds.height > 0f &&
        bounds.right > 0f && bounds.bottom > 0f &&
        bounds.left < viewport.width && bounds.top < viewport.height

@Composable
private fun SavedProfileIdentityImage(
    posterFile: java.io.File?,
    preview: android.graphics.Bitmap?,
) {
    if (preview != null && !preview.isRecycled) {
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = "Foto guardada del perfil",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else if (posterFile != null) {
        AsyncImage(
            model = posterFile,
            contentDescription = "Foto guardada del perfil",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
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
        val initialFontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = initialFontSize,
            lineHeight = initialFontSize,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
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
