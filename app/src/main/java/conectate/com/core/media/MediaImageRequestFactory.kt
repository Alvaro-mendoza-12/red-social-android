package conectate.com.core.media

import android.content.Context
import coil.request.CachePolicy
import coil.request.ImageRequest
import conectate.com.data.localfallback.UbuntuStorageUrl

object MediaImageRequestFactory {
    internal const val OFFLINE_CHAT_IMAGE = "novvin_offline_chat_image"
    const val DEFAULT_PREVIEW_WIDTH = 1080
    const val AVATAR_PREVIEW_WIDTH = 384
    const val AVATAR_PREVIEW_QUALITY = 84
    const val MAX_FULLSCREEN_IMAGE_DIMENSION_PX = 4096

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

    fun preview(context: Context, url: String?): ImageRequest? {
        val data = previewUrl(url) ?: return null
        val cacheKey = UbuntuStorageUrl.stableCacheKey(data)
        return ImageRequest.Builder(context)
            .data(data)
            .setParameter(OFFLINE_CHAT_IMAGE, true)
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
