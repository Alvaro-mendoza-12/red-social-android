package conectate.com.util

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import conectate.com.core.media.StorageReadCredential
import conectate.com.core.media.StorageReadIdentity
import conectate.com.core.media.StorageReadSession
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.test.UnitTestApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StorageReadHttpDataSourceTest {
    private val a = StorageReadCredential(StorageReadIdentity("a", 1), "fake.jwt")
    @After fun reset() { StorageReadSession.install { null } }
    private fun spec(own: Boolean = true): DataSpec = DataSpec.Builder()
        .setUri(if (own) UbuntuStorageUrl.baseUrl+"/storage/v1/object/public/chat-media/a/file" else "https://external.test/public.mp4").build()

    @Test fun bufferedReadCannotContinueAfterLogoutAndCloseStillReleases() {
        var current: StorageReadCredential? = a
        StorageReadSession.install { current }
        val upstream = Upstream(); val guarded = StorageReadHttpDataSource(upstream)
        guarded.open(spec()); assertEquals(1, guarded.read(ByteArray(1), 0, 1))
        current = null
        assertThrows(IOException::class.java) { guarded.read(ByteArray(1), 0, 1) }
        guarded.close(); assertEquals(1, upstream.closes)
    }
    @Test fun switchDuringReadDoesNotMutateCallerBuffer() {
        var current: StorageReadCredential? = a
        StorageReadSession.install { current }
        val upstream = Upstream { current = null }; val guarded = StorageReadHttpDataSource(upstream)
        guarded.open(spec()); val output = byteArrayOf(7, 8)
        assertThrows(IOException::class.java) { guarded.read(output, 0, 2) }
        assertArrayEquals(byteArrayOf(7, 8), output); guarded.close()
    }
    @Test fun reloginSameAccountWithAnotherGenerationCannotReadOldSource() {
        var current: StorageReadCredential? = a
        StorageReadSession.install { current }
        val guarded = StorageReadHttpDataSource(Upstream()); guarded.open(spec())
        current = StorageReadCredential(StorageReadIdentity("a", 2), "new.jwt")
        assertThrows(IOException::class.java) { guarded.read(ByteArray(1), 0, 1) }; guarded.close()
    }
    @Test fun externalPublicSourcePreservesPreviousBehavior() {
        var current: StorageReadCredential? = a
        StorageReadSession.install { current }
        val guarded = StorageReadHttpDataSource(Upstream()); guarded.open(spec(false))
        current = null; assertEquals(1, guarded.read(ByteArray(1), 0, 1)); guarded.close()
    }
    @Test fun tokenRefreshWithinTheSameIdentityStillReads() {
        var current: StorageReadCredential? = a
        StorageReadSession.install { current }
        val guarded = StorageReadHttpDataSource(Upstream()); guarded.open(spec())
        current = StorageReadCredential(a.identity, "new.jwt")
        assertEquals(1, guarded.read(ByteArray(1), 0, 1)); guarded.close()
    }
    @Test fun privateSelectorDoesNotOpenReusableSeekCacheAcrossAccounts() {
        var current: StorageReadCredential? = a
        StorageReadSession.install { current }
        val upstream = Upstream(); val reusable = Upstream()
        val selector = StorageReadDataSource({ upstream }, { reusable })
        selector.open(spec()); selector.close()
        current = StorageReadCredential(StorageReadIdentity("b", 1), "other.jwt")
        selector.open(spec()); selector.close()
        assertEquals(2, upstream.opens); assertEquals(0, reusable.opens)
    }
    private class Upstream(private val duringRead: () -> Unit = {}) : HttpDataSource {
        var opens = 0; var closes = 0
        private var spec: DataSpec? = null
        override fun open(dataSpec: DataSpec): Long { opens++; spec = dataSpec; return 10 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { buffer[offset] = 42; duringRead(); return 1 }
        override fun close() { closes++; spec = null }
        override fun getUri(): Uri? = spec?.uri
        override fun getResponseCode(): Int = 200
        override fun getResponseHeaders(): Map<String, List<String>> = mapOf("Content-Type" to listOf("video/mp4"))
        override fun addTransferListener(listener: TransferListener) {}
        override fun setRequestProperty(name: String, value: String) {}
        override fun clearRequestProperty(name: String) {}
        override fun clearAllRequestProperties() {}
    }
}
