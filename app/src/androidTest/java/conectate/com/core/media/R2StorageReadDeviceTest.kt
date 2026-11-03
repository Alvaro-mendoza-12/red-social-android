package conectate.com.core.media

import android.content.Context
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.BuildConfig
import conectate.com.core.testing.MediaAuditEntryPoint
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.util.MediaPlaybackDataSources
import conectate.com.util.StorageReadHttpDataSource
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Opt-in connected read of public content. No member account, message, upload or view event is created. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class R2StorageReadDeviceTest {
    @Test fun media3ReadsTheSameAuthorizedRangeFromR2AndUbuntuAndRejectsAnInvalidSession(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue("Live public guest reads require an explicit connected-test opt-in",
            InstrumentationRegistry.getArguments().getString("allowLiveGuestRead") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val guest = EntryPointAccessors.fromApplication(context, MediaAuditEntryPoint::class.java).guestModeApi()
        val hadGuest = guest.hasActiveGuest()
        val preview = guest.beginMediaPreview()
        try {
            guest.ensureIdentity()
            val url = guest.videos().firstNotNullOfOrNull { content ->
                UbuntuStorageUrl.rewrite(content.videoUrl)?.takeIf {
                    StorageReadPolicy.accepts(it, UbuntuStorageUrl.baseUrl) &&
                        it.contains("/storage/v1/object/public/")
                }
            } ?: error("No public own-storage video is available for the connected read")
            assertTrue("The QA build must include the media Worker", BuildConfig.MEDIA_EDGE_ORIGIN.isNotBlank())
            val accelerated = readPrefix(MediaPlaybackDataSources.httpFactory(), url)
            assertEquals("r2", accelerated.source)

            val directClient = MediaPlaybackDataSources.networkClient().newBuilder().apply {
                interceptors().removeAll { it is StorageReadRequestInterceptor }
                addInterceptor(StorageReadRequestInterceptor(edgeOrigin = ""))
                callTimeout(25, TimeUnit.SECONDS)
            }.build()
            val direct = readPrefix(StorageReadHttpDataSource.Factory(OkHttpDataSource.Factory(directClient)), url)
            assertArrayEquals(direct.prefixDigest, accelerated.prefixDigest)

            val invalid = StorageReadCredential(StorageReadIdentity("guest_probe", 1), "audit101.invalid.session")
            for (edge in listOf(BuildConfig.MEDIA_EDGE_ORIGIN, "")) {
                val invalidClient = MediaPlaybackDataSources.networkClient().newBuilder().apply {
                    interceptors().removeAll { it is StorageReadRequestInterceptor }
                    networkInterceptors().removeAll { it is StorageReadNetworkInterceptor }
                    addInterceptor(StorageReadRequestInterceptor(credentials = { invalid },
                        prepareCredentials = { invalid }, edgeOrigin = edge))
                    addNetworkInterceptor(StorageReadNetworkInterceptor(credentials = { invalid }))
                    callTimeout(25, TimeUnit.SECONDS)
                }.build()
                invalidClient.newCall(Request.Builder().url(url).header("Range", "bytes=0-63").build()).execute().use {
                    assertEquals("An invalid session must not read even a public guest object", 401, it.code)
                }
            }
            android.util.Log.i("R2DeviceRead", "PASS: Media3 range=4096; same-prefix=true; source=r2; invalid-sessions=401,401")
        } finally {
            guest.endMediaPreview(preview)
            if (!hadGuest) guest.clear()
        }
    }

    private data class PrefixRead(val prefixDigest: ByteArray, val source: String?)

    private fun readPrefix(factory: HttpDataSource.Factory, url: String): PrefixRead {
        val source = factory.createDataSource()
        try {
            assertEquals(4096L, source.open(DataSpec.Builder().setUri(url).setLength(4096).build()))
            assertEquals(206, source.responseCode)
            val bytes = ByteArray(4096)
            var offset = 0
            while (offset < bytes.size) {
                val count = source.read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                check(count > 0) { "The reader must make progress" }
                offset += count
            }
            assertEquals("The requested range must be complete", bytes.size, offset)
            return PrefixRead(MessageDigest.getInstance("SHA-256").digest(bytes),
                source.responseHeaders.entries.firstOrNull { it.key.equals("X-Novvin-Media-Source", true) }
                    ?.value?.singleOrNull())
        } finally { source.close() }
    }
}
