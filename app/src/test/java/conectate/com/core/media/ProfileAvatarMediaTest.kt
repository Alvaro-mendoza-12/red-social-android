package conectate.com.core.media

import android.app.Application
import conectate.com.data.localfallback.UbuntuStorageUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProfileAvatarMediaTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val source get() = UbuntuStorageUrl.baseUrl.trimEnd('/') + "/storage/v1/object/public/avatars/person/avatar"

    @Test fun `GIF chosen as profile photo requests original animated bytes instead of static thumbnail`() {
        val url = "$source.gif?width=384&format=webp"
        val request = checkNotNull(profileAvatarImageRequest(context, url, "gif"))
        assertEquals(UbuntuStorageUrl.originalObjectUrl(url), request.data)
        assertTrue(request.memoryCacheKey?.key?.startsWith("animated-source:") == true)
    }

    @Test fun `GIF and WebP avatar animation is preserved when inbox does not carry media type`() {
        listOf(".gif", ".WEBP", ".apng").forEach { extension ->
            val url = "$source$extension?token=signature#profile"
            val request = checkNotNull(profileAvatarImageRequest(context, url, null))
            assertEquals(UbuntuStorageUrl.originalObjectUrl(url), request.data)
            assertTrue(request.memoryCacheKey?.key?.startsWith("animated-source:") == true)
        }
    }

    @Test fun `MIME hint preserves animated source for an opaque object name`() {
        val request = checkNotNull(profileAvatarImageRequest(context, source, "image/gif"))
        assertEquals(UbuntuStorageUrl.originalObjectUrl(source), request.data)
        assertTrue(request.memoryCacheKey?.key?.startsWith("animated-source:") == true)
    }

    @Test fun `ordinary photographs retain bounded static thumbnail request`() {
        val url = "$source.jpg"
        val request = checkNotNull(profileAvatarImageRequest(context, url, "image"))
        assertEquals(MediaImageRequestFactory.avatarUrl(url), request.data)
        assertEquals(MediaImageRequestFactory.avatarCacheKey(url), request.memoryCacheKey?.key)
    }

    @Test fun `video formats use player path even when inbox does not carry MIME hint`() {
        listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".3gp").forEach { extension ->
            val url = "$source$extension?token=signature#profile"
            assertTrue(isVideoProfileAvatar(url, null))
            assertNull(profileAvatarImageRequest(context, url, null))
        }
        assertTrue(isVideoProfileAvatar(source, "video/webm"))
    }
}
