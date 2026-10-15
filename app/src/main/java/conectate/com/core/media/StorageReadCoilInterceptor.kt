package conectate.com.core.media

import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageResult
import java.io.IOException

/** Cache visibility cannot be inferred from a bucket or a signed/public URL label. */
internal class StorageReadCoilInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val original = chain.request
        if (!StorageReadSession.isStorageRequest(original.data.toString())) return chain.proceed(original)
        val identity = StorageReadSession.snapshot()?.identity
        val request = original.newBuilder()
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            // Remove an old account's preview before EngineInterceptor accesses placeholders.
            .placeholderMemoryCacheKey(null as String?)
            .removeHeader("Authorization")
            .build()
        val result = chain.proceed(request)
        return try { StorageReadSession.requireCurrent(identity); result }
        catch (changed: IOException) { ErrorResult(null, request, changed) }
    }
}
