package conectate.com.core.media

import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Cache visibility cannot be inferred from a bucket or a signed/public URL label. */
internal class StorageReadCoilInterceptor(
    private val cachedImage: suspend (String, StorageReadIdentity) -> String? = { _, _ -> null },
    private val retainImage: (String, StorageReadIdentity) -> Unit = { _, _ -> },
    private val retainPreview: (String, StorageReadIdentity, android.graphics.drawable.Drawable) -> Unit = { _, _, _ -> },
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val original = chain.request
        if (!StorageReadSession.isStorageRequest(original.data.toString())) return chain.proceed(original)
        val source = original.data.toString()
        val identity = StorageReadSession.snapshot()?.identity
        val offlineAllowed = identity != null && !identity.accountId.startsWith("guest_") &&
            original.parameters.value<Boolean>(MediaImageRequestFactory.OFFLINE_CHAT_IMAGE) == true
        // A guest may reuse decoded public previews within its own generation.
        // It still cannot retain files on disk or borrow a member's cache key.
        val memoryAllowed = identity != null && (
            original.parameters.value<Boolean>(MediaImageRequestFactory.OFFLINE_CHAT_IMAGE) == true ||
                original.parameters.value<Boolean>(MediaImageRequestFactory.MEMORY_ONLY_PREVIEW) == true)
        val cached = if (offlineAllowed) {
            try {
                StorageReadSession.requireCurrent(identity)
                cachedImage(source, checkNotNull(identity)).also { StorageReadSession.requireCurrent(identity) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IOException) { null }
        } else null
        val request = original.newBuilder()
            .data(cached ?: original.data)
            .memoryCachePolicy(if (memoryAllowed) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .memoryCacheKey(if (memoryAllowed) StorageReadSession.scopedCacheKey(source,
                original.memoryCacheKey?.key ?: source) else null)
            .diskCachePolicy(CachePolicy.DISABLED)
            // Remove an old account's preview before EngineInterceptor accesses placeholders.
            .placeholderMemoryCacheKey(null as String?)
            .removeHeader("Authorization")
            .build()
        return try {
            StorageReadSession.requireCurrent(identity)
            val result = chain.proceed(request)
            StorageReadSession.requireCurrent(identity)
            val speculative = original.parameters.value<Boolean>(MediaImageRequestFactory.PREFETCH_CHAT_IMAGE) == true
            if (offlineAllowed && !speculative && result is SuccessResult) {
                if (cached == null) runCatching { retainPreview(source, checkNotNull(identity), result.drawable) }
                if (original.parameters.value<Boolean>(MediaImageRequestFactory.AVATAR_THUMBNAIL) != true) {
                    runCatching { retainImage(source, checkNotNull(identity)) }
                }
            }
            result
        }
        catch (changed: IOException) { ErrorResult(null, request, changed) }
    }
}
