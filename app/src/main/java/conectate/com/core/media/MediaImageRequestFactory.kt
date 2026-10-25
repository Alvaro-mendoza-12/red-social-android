package conectate.com.core.media

import android.content.Context
import coil.ImageLoader
import coil.decode.Decoder
import coil.decode.VideoFrameDecoder
import coil.fetch.SourceResult
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import coil.request.videoFrameMillis
import conectate.com.data.localfallback.UbuntuStorageUrl

object MediaImageRequestFactory {
    internal const val OFFLINE_CHAT_IMAGE = "novvin_offline_chat_image"
    internal const val PREFETCH_CHAT_IMAGE = "novvin_prefetch_chat_image"
    internal const val MEMORY_ONLY_PREVIEW = "novvin_memory_only_preview"
    const val DEFAULT_PREVIEW_WIDTH = 1080
    const val AVATAR_PREVIEW_WIDTH = 384
    const val AVATAR_PREVIEW_QUALITY = 84
    const val MAX_FULLSCREEN_IMAGE_DIMENSION_PX = 4096

    /** Offline chat videos are named .media and arrive without a video MIME hint. */
    private val opaqueVideoDecoder = object : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder =
            VideoFrameDecoder(result.source, options)
    }

    /** The image interceptor resolves account-local copies without changing the UI model. */
    fun chatPreviewSource(source: String?, resolvedSource: String?): String? {
        val original = UbuntuStorageUrl.rewrite(source)
        return if (StorageReadSession.isStorageRequest(original)) original
        else UbuntuStorageUrl.rewrite(resolvedSource ?: source)
    }

    fun avatarUrl(url: String?): String? {
        val original = UbuntuStorageUrl.originalObjectUrl(url)
            ?: UbuntuStorageUrl.rewrite(url)
        return UbuntuStorageUrl.thumbnail(
            original,
            width = AVATAR_PREVIEW_WIDTH,
            quality = AVATAR_PREVIEW_QUALITY,
        ) ?: original
    }

    fun avatarCacheKey(url: String?): String? =
        UbuntuStorageUrl.stableCacheKey(avatarUrl(url))

    fun avatar(context: Context, url: String?): ImageRequest? {
        val data = avatarUrl(url) ?: return null
        val cacheKey = UbuntuStorageUrl.stableCacheKey(data)
        return ImageRequest.Builder(context)
            .data(data)
            .setParameter(OFFLINE_CHAT_IMAGE, true)
            .size(AVATAR_PREVIEW_WIDTH, AVATAR_PREVIEW_WIDTH)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .placeholderMemoryCacheKey(if (StorageReadSession.isStorageRequest(data)) null as String? else cacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    fun previewUrl(url: String?): String? {
        val original = UbuntuStorageUrl.originalObjectUrl(url)
            ?: UbuntuStorageUrl.rewrite(url)
        return UbuntuStorageUrl.thumbnail(original, width = DEFAULT_PREVIEW_WIDTH)
            ?: original
    }

    fun previewCacheKey(url: String?): String? =
        UbuntuStorageUrl.stableCacheKey(previewUrl(url))

    /** Force video decoding for opaque offline files that have no video extension/MIME. */
    fun videoPoster(context: Context, videoUrl: String?, thumbnailUrl: String?): ImageRequest? {
        val thumbnail = thumbnailUrl?.takeIf(String::isNotBlank)
        val source = if (thumbnail != null) UbuntuStorageUrl.thumbnail(thumbnail, width = 720)
            else UbuntuStorageUrl.rewrite(videoUrl)
        if (source.isNullOrBlank()) return null
        val key = UbuntuStorageUrl.stableCacheKey(source)?.let { "chat-video-poster:$it" }
        return ImageRequest.Builder(context)
            .data(source)
            .size(720, 720)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .apply {
                if (thumbnail == null) {
                    decoderFactory(opaqueVideoDecoder)
                    videoFrameMillis(1_000)
                    setParameter(MEMORY_ONLY_PREVIEW, true)
                } else setParameter(OFFLINE_CHAT_IMAGE, true)
            }
            .crossfade(false)
            .build()
    }

    fun preview(context: Context, url: String?): ImageRequest? {
        val data = previewUrl(url) ?: return null
        val cacheKey = UbuntuStorageUrl.stableCacheKey(data)
        return ImageRequest.Builder(context)
            .data(data)
            .setParameter(OFFLINE_CHAT_IMAGE, true)
            .size(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_WIDTH)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    /**
     * Animated media must use the original object. The Ubuntu thumbnail endpoint
     * intentionally returns a bounded static WebP, which would freeze GIF/WebP stickers.
     */
    fun animated(context: Context, url: String?): ImageRequest? {
        val data = UbuntuStorageUrl.originalObjectUrl(url) ?: return null
        val sourceKey = UbuntuStorageUrl.stableCacheKey(data)
        val animatedKey = sourceKey?.let { "animated-source:$it" }
        return ImageRequest.Builder(context)
            .data(data)
            .setParameter(OFFLINE_CHAT_IMAGE, true)
            .memoryCacheKey(animatedKey)
            .diskCacheKey(animatedKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    fun fullScreen(context: Context, url: String?): ImageRequest? {
        val data = UbuntuStorageUrl.originalObjectUrl(url) ?: return null
        val sourceKey = UbuntuStorageUrl.stableCacheKey(data)
        val previewKey = previewCacheKey(data)
        return ImageRequest.Builder(context)
            .data(data)
            .setParameter(OFFLINE_CHAT_IMAGE, true)
            // Enough source pixels for a 3x zoom on common phone displays without
            // decoding an untrusted 50 MP upload into hundreds of megabytes of RAM.
            .size(MAX_FULLSCREEN_IMAGE_DIMENSION_PX, MAX_FULLSCREEN_IMAGE_DIMENSION_PX)
            .memoryCacheKey(sourceKey?.let { "$it#fullscreen" })
            .diskCacheKey(sourceKey)
            .placeholderMemoryCacheKey(if (StorageReadSession.isStorageRequest(data)) null as String? else previewKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }
}
