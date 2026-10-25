package conectate.com.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import coil.Coil
import coil.request.SuccessResult
import coil.request.CachePolicy
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.util.NetworkConnectivityObserver
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Durable, account-bound fallback for profile identity images.
 *
 * Coil and Media3 caches are intentionally evictable. This store keeps one
 * small image for an identity already delivered to the device: a static avatar
 * or the rendered poster of an animated avatar. It lives in noBackupFilesDir so
 * it survives process restarts but is neither cloud-backed nor exposed by
 * FileProvider.
 */
object ProfileIdentityImageStore {
    private const val TAG = "ProfileIdentityStore"
    private const val ROOT_DIRECTORY = "profile-identity-images-v1"
    private const val MAX_IMAGES = 96
    private const val MAX_TOTAL_BYTES = 24L * 1024L * 1024L
    private const val IMAGE_QUALITY = 84
    internal const val MAX_IMAGE_DIMENSION = 512
    private const val MAX_PREVIEW_DIMENSION = 256
    private const val WARM_TIMEOUT_MILLIS = 6_000L
    private val writeMutex = Mutex()
    private val warmSemaphore = Semaphore(3)
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmingKeys = ConcurrentHashMap.newKeySet<String>()
    private val cachedIdentityFiles = ConcurrentHashMap<String, File>()
    private val retainingKeys = ConcurrentHashMap.newKeySet<String>()
    private val identityMemoryLock = Any()
    // Keep decoded pixels ready for the first frame after navigation. Eviction
    // drops only our reference: Compose may still be displaying the bitmap.
    private val cachedIdentityPreviews = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val previewMutex = Mutex()
    private val writeFence = ProfileIdentityWriteFence()

    fun generation(accountId: String?): Long = writeFence.capture(accountId)

    suspend fun usage(context: Context, accountId: String): ProfileIdentityUsage? = withContext(Dispatchers.IO) {
        try { writeMutex.withLock { ProfileIdentityFiles(root(context)).usage(accountId) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun clear(context: Context, accountId: String, expectedAccountKey: String): Boolean = withContext(Dispatchers.IO) {
        try { writeMutex.withLock {
            if (ProfileIdentityFiles.accountKey(accountId) != expectedAccountKey) return@withLock false
            synchronized(identityMemoryLock) { writeFence.invalidate(accountId) }
            ProfileIdentityFiles(root(context)).clear(accountId, expectedAccountKey).also { cleared ->
                if (cleared) {
                    val prefix = "${expectedAccountKey}:"
                    synchronized(identityMemoryLock) {
                        cachedIdentityFiles.keys.removeIf { it.startsWith(prefix) }
                        cachedIdentityPreviews.snapshot().keys.filter { it.startsWith(prefix) }
                            .forEach(cachedIdentityPreviews::remove)
                    }
                }
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    /** Returns an already-known private avatar file without waiting for disk lookup. */
    fun peek(sourceUrl: String?, accountId: String? = null): File? {
        val key = sourceKey(sourceUrl) ?: return null
        val memoryKey = memoryKey(accountId, key) ?: return null
        return cachedIdentityFiles[memoryKey]
    }

    /** Memory only; safe to use during composition without decoding or disk I/O. */
    fun peekPreview(sourceUrl: String?, accountId: String? = null): Bitmap? {
        val key = sourceKey(sourceUrl)?.let { memoryKey(accountId, it) } ?: return null
        return cachedIdentityPreviews.get(key)?.takeUnless(Bitmap::isRecycled)
    }

    /**
     * Called from the image-success callback on Main. BitmapDrawable pixels are
     * already decoded; only animated/custom drawables need a bounded snapshot.
     * The queued write owns neither the drawable nor a UI callback and outlives
     * the composable that originally displayed this avatar.
     */
    fun retainDrawable(
        context: Context,
        sourceUrl: String?,
        drawable: Drawable,
        accountId: String? = null,
        expectedGeneration: Long = generation(accountId),
    ): Bitmap? = retain(context, sourceUrl, accountId, expectedGeneration) {
        identityDrawableBitmap(drawable)
    }

    private fun identityDrawableBitmap(drawable: Drawable): Bitmap =
        if (drawable is BitmapDrawable) drawable.bitmap else {
            val dimensions = profileIdentityPreviewDimensions(drawable.intrinsicWidth, drawable.intrinsicHeight)
            drawable.toBitmap(dimensions.first, dimensions.second)
        }

    /** Keeps a captured video frame without making the UI wait for durable I/O. */
    fun retainBitmap(
        context: Context,
        sourceUrl: String?,
        bitmap: Bitmap,
        accountId: String? = null,
        expectedGeneration: Long = generation(accountId),
    ): Bitmap? = retain(context, sourceUrl, accountId, expectedGeneration) { bitmap }

    private inline fun retain(
        context: Context,
        sourceUrl: String?,
        accountId: String?,
        expectedGeneration: Long,
        snapshot: () -> Bitmap,
    ): Bitmap? {
        val sourceKey = sourceKey(sourceUrl) ?: return null
        val key = memoryKey(accountId, sourceKey) ?: return null
        if (!writeFence.isCurrent(accountId, expectedGeneration)) return null
        peekPreview(sourceUrl, accountId)?.let { return it }
        val workKey = "$key:$expectedGeneration"
        if (!retainingKeys.add(workKey)) return peekPreview(sourceUrl, accountId)
        try {
            val bitmap = snapshot().takeUnless(Bitmap::isRecycled) ?: run {
                retainingKeys.remove(workKey)
                return null
            }
            if (!publishPreview(key, bitmap, accountId, expectedGeneration)) {
                retainingKeys.remove(workKey)
                return null
            }
            val appContext = context.applicationContext
            warmScope.launch {
                try {
                    save(appContext, sourceUrl, bitmap, accountId, expectedGeneration)
                } finally {
                    retainingKeys.remove(workKey)
                }
            }
            return bitmap
        } catch (_: Exception) {
            retainingKeys.remove(workKey)
            // Persisting a fallback must never fail the visible image or animation.
            return null
        }
    }

    private fun publishPreview(key: String, bitmap: Bitmap, accountId: String?, expectedGeneration: Long): Boolean =
        synchronized(identityMemoryLock) {
            if (!writeFence.isCurrent(accountId, expectedGeneration)) false
            else { cachedIdentityPreviews.put(key, bitmap); true }
        }

    /** Runs off Main, making our bounded copy independent from Coil's pixels. */
    private fun cacheBitmapPreview(bitmap: Bitmap, sourceKey: String, accountId: String?, expectedGeneration: Long) {
        val key = memoryKey(accountId, sourceKey) ?: return
        val existing = cachedIdentityPreviews.get(key)
        if (existing != null && existing !== bitmap && !existing.isRecycled &&
            maxOf(existing.width, existing.height) <= MAX_PREVIEW_DIMENSION
        ) return
        val dimensions = profileIdentityPreviewDimensions(bitmap.width, bitmap.height)
        val preview = if (dimensions.first == bitmap.width && dimensions.second == bitmap.height) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            val software = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
            try { software.scale(dimensions.first, dimensions.second, true) }
            finally { software.recycle() }
        } else bitmap.scale(dimensions.first, dimensions.second, true)
        // Never recycle a published bitmap: an older Compose frame may own it.
        publishPreview(key, preview, accountId, expectedGeneration)
    }

    private suspend fun cachePreview(file: File, sourceKey: String, accountId: String?, expectedGeneration: Long) {
        previewMutex.withLock {
            val key = memoryKey(accountId, sourceKey) ?: return@withLock
            if (!writeFence.isCurrent(accountId, expectedGeneration) || cachedIdentityPreviews.get(key) != null) return@withLock
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withLock
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withLock
            val ratio = minOf(1f, MAX_PREVIEW_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height))
            val preview = if (ratio < 1f) decoded.scale(
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1), true,
            ) else decoded
            if (preview !== decoded) decoded.recycle()
            if (!publishPreview(key, preview, accountId, expectedGeneration)) preview.recycle()
        }
    }

    suspend fun find(context: Context, sourceUrl: String?, accountId: String? = null, expectedGeneration: Long = generation(accountId)): File? =
        withContext(Dispatchers.IO) {
            try {
            if (!writeFence.isCurrent(accountId, expectedGeneration)) return@withContext null
            val key = sourceKey(sourceUrl) ?: return@withContext null
            val files = ProfileIdentityFiles(root(context))
            files.find(accountId, key)?.let {
                memoryKey(accountId, key)?.let { cacheKey -> cachedIdentityFiles[cacheKey] = it }
                cachePreview(it, key, accountId, expectedGeneration)
                return@withContext it
            }
            memoryKey(accountId, key)?.let(cachedIdentityFiles::remove)
            if (accountId.isNullOrBlank()) return@withContext null
            if (!files.canMigrateLegacy(accountId)) return@withContext null
            // Upgrade only the exact legacy URL requested for this account, never
            // an entire shared directory or another account's private directory.
            val legacy = files.find(null, key) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(legacy.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight > 64_000_000L
            ) return@withContext null
            val options = BitmapFactory.Options().apply {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_DIMENSION * 2) sample *= 2
                inSampleSize = sample
            }
            val bitmap = BitmapFactory.decodeFile(legacy.absolutePath, options) ?: return@withContext null
            try {
                val saved = save(context, sourceUrl, bitmap, accountId, expectedGeneration)
                if (writeFence.isCurrent(accountId, expectedGeneration)) saved ?: legacy else null
            } finally { bitmap.recycle() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }

    /** Warms static inbox identities without holding up Room or UI delivery. */
    fun warm(context: Context, sourceUrls: Iterable<String>, accountId: String? = null, expectedGeneration: Long = generation(accountId)) {
        if (accountId != null && accountId.isBlank()) return
        val appContext = context.applicationContext
        val files = ProfileIdentityFiles(root(appContext))
        val pending = sourceUrls.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(::isStaticProfileIdentitySource)
            .distinctBy(::sourceKey)
            .mapNotNull { sourceUrl ->
                val key = sourceKey(sourceUrl) ?: return@mapNotNull null
                val path = files.imageFile(accountId, key)?.absolutePath ?: return@mapNotNull null
                val workKey = "$path:$expectedGeneration"
                if (!warmingKeys.add(workKey)) null else sourceUrl to workKey
            }.toList()
        // At most three workers, not a coroutine/network request for every contact.
        warmScope.launch {
            try {
                warmProfileIdentityBatch(pending) { (sourceUrl, workKey) ->
                    try {
                        warmSemaphore.withPermit {
                            if (!writeFence.isCurrent(accountId, expectedGeneration)) return@withPermit
                            if (find(appContext, sourceUrl, accountId, expectedGeneration) != null) return@withPermit
                            withTimeoutOrNull(WARM_TIMEOUT_MILLIS) {
                                val baseRequest = profileAvatarImageRequest(appContext, sourceUrl, null)
                                    ?: return@withTimeoutOrNull
                                val online = runCatching { NetworkConnectivityObserver(appContext).isNetworkAvailable() }.getOrDefault(false)
                                val request = if (online) baseRequest else baseRequest.newBuilder()
                                    .networkCachePolicy(CachePolicy.DISABLED).build()
                                val result = Coil.imageLoader(appContext).execute(request)
                                if (result is SuccessResult) {
                                    val bitmap = if (result.drawable is BitmapDrawable) {
                                        (result.drawable as BitmapDrawable).bitmap
                                    } else withContext(Dispatchers.Main.immediate) {
                                        identityDrawableBitmap(result.drawable)
                                    }
                                    save(appContext, sourceUrl, bitmap, accountId, expectedGeneration)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Do not log signed URLs, account identifiers or request details.
                        Log.d(TAG, "Static avatar warmup deferred")
                    } finally {
                        warmingKeys.remove(workKey)
                    }
                }
            } finally {
                pending.forEach { (_, workKey) -> warmingKeys.remove(workKey) }
            }
        }
    }

    suspend fun save(context: Context, sourceUrl: String?, bitmap: Bitmap, accountId: String? = null, expectedGeneration: Long = generation(accountId)): File? =
        withContext(Dispatchers.IO) {
            try { writeMutex.withLock {
                if (!writeFence.isCurrent(accountId, expectedGeneration)) return@withLock null
                val directory = root(context).apply { mkdirs() }
                val key = sourceKey(sourceUrl) ?: return@withLock null
                val files = ProfileIdentityFiles(directory)
                val target = files.imageFile(accountId, key) ?: return@withLock null
                if (bitmap.isRecycled) return@withLock null
                // Pixel continuity must not wait for encoding, fsync, or a second
                // decode. The input itself can still belong to Coil or the UI.
                cacheBitmapPreview(bitmap, key, accountId, expectedGeneration)
                val dimensions = profileIdentityDimensions(bitmap.width, bitmap.height)
                if (existingImageIsAtLeast(target, dimensions.first, dimensions.second)) {
                    memoryKey(accountId, key)?.let { cachedIdentityFiles[it] = target }
                    cachePreview(target, key, accountId, expectedGeneration)
                    touch(target)
                    return@withLock target
                }

                var softwareBitmap: Bitmap? = null
                var scaledBitmap: Bitmap? = null
                try {
                    val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
                        bitmap.copy(Bitmap.Config.ARGB_8888, false)?.also { softwareBitmap = it }
                            ?: return@withLock null
                    } else bitmap
                    val encodable = source.scale(dimensions.first, dimensions.second, true)
                        .also { if (it !== source) scaledBitmap = it }
                    val bytes = ByteArrayOutputStream().use { output ->
                        check(encodable.compress(profileCompressFormat(), IMAGE_QUALITY, output)) {
                            "Could not encode profile identity image"
                        }
                        output.toByteArray()
                    }
                    currentCoroutineContext().ensureActive()
                    files.write(accountId, key, bytes)?.also {
                        memoryKey(accountId, key)?.let { cacheKey -> cachedIdentityFiles[cacheKey] = it }
                        cachePreview(it, key, accountId, expectedGeneration)
                        touch(it)
                        if (accountId == null) prune(directory)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w(TAG, "Unable to persist profile identity image")
                    null
                } finally {
                    scaledBitmap?.recycle()
                    softwareBitmap?.recycle()
                }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }

    internal fun imageFile(directory: File, sourceUrl: String?): File? {
        val key = sourceKey(sourceUrl) ?: return null
        return ProfileIdentityFiles(directory).imageFile(null, key)
    }

    internal fun prune(
        directory: File,
        maxImages: Int = MAX_IMAGES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ) {
        val candidates = directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("webp", ignoreCase = true) }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var retainedBytes = 0L
        candidates.forEachIndexed { index, file ->
            val nextBytes = retainedBytes + file.length().coerceAtLeast(0L)
            if (index >= maxImages.coerceAtLeast(0) || nextBytes > maxTotalBytes.coerceAtLeast(0L)) {
                file.delete()
            } else {
                retainedBytes = nextBytes
            }
        }
        directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("tmp", ignoreCase = true) }
            ?.forEach(File::delete)
    }

    private fun root(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, ROOT_DIRECTORY)

    private fun existingImageIsAtLeast(file: File, width: Int, height: Int): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth >= width && options.outHeight >= height
    }

    private fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    private fun sourceKey(sourceUrl: String?): String? = sourceUrl
        ?.let(UbuntuStorageUrl::rewrite)
        ?.let(UbuntuStorageUrl::stableCacheKey)
        ?.takeIf(String::isNotBlank)

    private fun memoryKey(accountId: String?, sourceKey: String): String? {
        if (accountId == null) return "anonymous:$sourceKey"
        val accountKey = ProfileIdentityFiles.accountKey(accountId) ?: return null
        return "$accountKey:$sourceKey"
    }

    @Suppress("DEPRECATION")
    private fun profileCompressFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

}

internal fun profileIdentityPreviewDimensions(width: Int, height: Int): Pair<Int, Int> {
    val maximum = maxOf(width, height, 1)
    val scale = minOf(1.0, 256.0 / maximum)
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

internal fun profileIdentityDimensions(width: Int, height: Int): Pair<Int, Int> {
    val maximum = maxOf(width, height, 1)
    val scale = minOf(1.0, ProfileIdentityImageStore.MAX_IMAGE_DIMENSION.toDouble() / maximum)
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

internal fun isStaticProfileIdentitySource(source: String): Boolean {
    val path = source.substringBefore('?').substringBefore('#').lowercase(java.util.Locale.ROOT)
    return listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".3gp").none(path::endsWith)
}
