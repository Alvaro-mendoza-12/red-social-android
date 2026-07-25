package conectate.com.core.media

import android.content.Context
import coil.request.CachePolicy
import coil.request.ImageRequest
import conectate.com.data.localfallback.UbuntuStorageUrl

object MediaImageRequestFactory {
    const val DEFAULT_PREVIEW_WIDTH = 1080

    fun previewUrl(url: String?): String? =
        UbuntuStorageUrl.thumbnail(url, width = DEFAULT_PREVIEW_WIDTH)
            ?: UbuntuStorageUrl.rewrite(url)

    fun previewCacheKey(url: String?): String? =
        UbuntuStorageUrl.stableCacheKey(previewUrl(url))

    fun preview(context: Context, url: String?): ImageRequest? {
        val data = previewUrl(url) ?: return null
        val cacheKey = UbuntuStorageUrl.stableCacheKey(data)
        return ImageRequest.Builder(context)
            .data(data)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    fun fullScreen(context: Context, url: String?): ImageRequest? {
        val data = UbuntuStorageUrl.rewrite(url) ?: return null
        val sourceKey = UbuntuStorageUrl.stableCacheKey(data)
        val previewKey = previewCacheKey(data)
        return ImageRequest.Builder(context)
            .data(data)
            .memoryCacheKey(sourceKey?.let { "$it#fullscreen" })
            .diskCacheKey(sourceKey)
            .placeholderMemoryCacheKey(previewKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }
}
