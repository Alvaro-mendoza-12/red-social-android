package conectate.com.core.media

import android.app.Application
import android.graphics.drawable.ColorDrawable
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Size
import conectate.com.data.localfallback.UbuntuStorageUrl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class StorageReadCoilOfflineTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val source get() = UbuntuStorageUrl.baseUrl.trimEnd('/') + "/storage/v1/object/public/chat-media/a/sticker.webp"
    private var credential = StorageReadCredential(StorageReadIdentity("account-a", 1), "test.jwt")
    private fun install() { StorageReadSession.install { credential } }
    @After fun clear() { StorageReadSession.install { null }; StorageReadSession.installGuest(credentials = { null }) }

    @Test fun `guest previews reuse only session scoped memory and never retain offline files`() = runBlocking {
        credential = StorageReadCredential(StorageReadIdentity("guest_qa", 3), "guest.jwt")
        StorageReadSession.installGuest(credentials = { credential }, active = { true })
        var touchedDisk = false
        val interceptor = StorageReadCoilInterceptor(
            cachedImage = { _, _ -> touchedDisk = true; null },
            retainImage = { _, _ -> touchedDisk = true },
            retainPreview = { _, _, _ -> touchedDisk = true },
        )
        val request = requireNotNull(MediaImageRequestFactory.preview(context, source))
        val first = Chain(request)
        assertTrue(interceptor.intercept(first) is SuccessResult)
        assertEquals(CachePolicy.ENABLED, first.sent?.memoryCachePolicy)
        assertEquals(CachePolicy.DISABLED, first.sent?.diskCachePolicy)
        val guestKey = first.sent?.memoryCacheKey
        credential = StorageReadCredential(StorageReadIdentity("guest_other", 4), "other.jwt")
        val second = Chain(request)
        interceptor.intercept(second)
        assertNotEquals(guestKey, second.sent?.memoryCacheKey)
        assertFalse(touchedDisk)
    }

    @Test fun `a guest session change during a memory hit cannot deliver the old pixels`() = runBlocking {
        credential = StorageReadCredential(StorageReadIdentity("guest_qa", 1), "guest.jwt")
        StorageReadSession.installGuest(credentials = { credential }, active = { true })
        val request = requireNotNull(MediaImageRequestFactory.preview(context, source))
        val chain = Chain(request, afterRead = {
            credential = StorageReadCredential(StorageReadIdentity("member", 2), "member.jwt")
        })
        assertTrue(StorageReadCoilInterceptor().intercept(chain) is ErrorResult)
    }

    @Test fun `received sticker uses its original local bytes without a network request`() = runBlocking {
        install()
        val request = requireNotNull(MediaImageRequestFactory.animated(context, source))
        val chain = Chain(request)
        val interceptor = StorageReadCoilInterceptor(cachedImage = { _, identity ->
            assertEquals("account-a", identity.accountId)
            "file:/private/account-a/sticker.media"
        })
        val result = interceptor.intercept(chain)
        assertTrue(result is SuccessResult)
        assertEquals("file:/private/account-a/sticker.media", chain.sent?.data)
        assertEquals(CachePolicy.DISABLED, chain.sent?.diskCachePolicy)
        assertEquals(CachePolicy.ENABLED, chain.sent?.memoryCachePolicy)
        assertTrue(chain.sent?.memoryCacheKey?.key?.startsWith("storage-read-v1:") == true)
    }

    @Test fun `successful first image requests a retained offline copy`() = runBlocking {
        install()
        var retained: String? = null
        val interceptor = StorageReadCoilInterceptor(retainImage = { url, identity ->
            assertEquals(credential.identity, identity)
            retained = url
        })
        val request = requireNotNull(MediaImageRequestFactory.preview(context, source))
        assertTrue(interceptor.intercept(Chain(request)) is SuccessResult)
        assertEquals(request.data.toString(), retained)
    }

    @Test fun `an avatar thumbnail retains the preview and downloads the original only when opened`() = runBlocking {
        install()
        val avatar = UbuntuStorageUrl.baseUrl.trimEnd('/') + "/storage/v1/object/public/avatars/account-a/photo.jpg"
        val previews = mutableListOf<String>()
        val originals = mutableListOf<String>()
        val interceptor = StorageReadCoilInterceptor(
            retainImage = { url, _ -> originals += url },
            retainPreview = { url, _, _ -> previews += url },
        )
        val thumbnail = requireNotNull(MediaImageRequestFactory.avatar(context, avatar))
        assertTrue(interceptor.intercept(Chain(thumbnail)) is SuccessResult)
        assertEquals(listOf(thumbnail.data.toString()), previews)
        assertTrue(originals.isEmpty())

        val opened = requireNotNull(MediaImageRequestFactory.fullScreen(context, avatar))
        assertTrue(interceptor.intercept(Chain(opened)) is SuccessResult)
        assertEquals(listOf(avatar), originals)
    }

    @Test fun `account switch while checking disk never returns the previous accounts image`() = runBlocking {
        install()
        val chain = Chain(requireNotNull(MediaImageRequestFactory.animated(context, source)))
        val interceptor = StorageReadCoilInterceptor(cachedImage = { _, _ ->
            credential = StorageReadCredential(StorageReadIdentity("account-b", 2), "other.jwt")
            "file:/private/account-a/sticker.media"
        })
        assertTrue(interceptor.intercept(chain) is ErrorResult)
        assertNull(chain.sent)
    }

    @Test fun `unmarked private requests never consult or retain offline media`() = runBlocking {
        install()
        var called = false
        val interceptor = StorageReadCoilInterceptor(
            cachedImage = { _, _ -> called = true; null },
            retainImage = { _, _ -> called = true },
        )
        val chain = Chain(ImageRequest.Builder(context).data(source).build())
        interceptor.intercept(chain)
        assertFalse(called)
        assertEquals(CachePolicy.DISABLED, chain.sent?.memoryCachePolicy)
        assertEquals(CachePolicy.DISABLED, chain.sent?.diskCachePolicy)
    }

    @Test fun `speculative thumbnails cannot become permanently retained images`() = runBlocking {
        install()
        var retained = false
        val interceptor = StorageReadCoilInterceptor(
            retainImage = { _, _ -> retained = true },
            retainPreview = { _, _, _ -> retained = true }
        )
        val request = requireNotNull(MediaImageRequestFactory.preview(context, source)).newBuilder()
            .setParameter(MediaImageRequestFactory.PREFETCH_CHAT_IMAGE, true).build()
        assertTrue(interceptor.intercept(Chain(request)) is SuccessResult)
        assertFalse(retained)
    }

    @Test fun `video frames use account memory without retaining bitmaps as playable videos`() = runBlocking {
        install()
        var usedOffline = false
        val interceptor = StorageReadCoilInterceptor(
            cachedImage = { _, _ -> usedOffline = true; null },
            retainImage = { _, _ -> usedOffline = true },
            retainPreview = { _, _, _ -> usedOffline = true },
        )
        val request = requireNotNull(MediaImageRequestFactory.videoPoster(context, source.replace("sticker.webp", "movie.media"), null))
        val chain = Chain(request)
        assertNotNull(request.decoderFactory)
        assertTrue(interceptor.intercept(chain) is SuccessResult)
        assertFalse(usedOffline)
        assertEquals(CachePolicy.ENABLED, chain.sent?.memoryCachePolicy)
        assertEquals(CachePolicy.DISABLED, chain.sent?.diskCachePolicy)
        assertTrue(chain.sent?.memoryCacheKey?.key?.startsWith("storage-read-v1:") == true)
    }

    private class Chain(override val request: ImageRequest, val afterRead: () -> Unit = {}) : Interceptor.Chain {
        var sent: ImageRequest? = null
        override val size = Size.ORIGINAL
        override fun withRequest(request: ImageRequest): Interceptor.Chain = Chain(request)
        override fun withSize(size: Size): Interceptor.Chain = this
        override suspend fun proceed(request: ImageRequest): ImageResult {
            sent = request
            afterRead()
            return SuccessResult(ColorDrawable(0), request, DataSource.NETWORK)
        }
    }
}
