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
    @After fun clear() { StorageReadSession.install { null } }

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

    private class Chain(override val request: ImageRequest) : Interceptor.Chain {
        var sent: ImageRequest? = null
        override val size = Size.ORIGINAL
        override fun withRequest(request: ImageRequest): Interceptor.Chain = Chain(request)
        override fun withSize(size: Size): Interceptor.Chain = this
        override suspend fun proceed(request: ImageRequest): ImageResult {
            sent = request
            return SuccessResult(ColorDrawable(0), request, DataSource.NETWORK)
        }
    }
}
