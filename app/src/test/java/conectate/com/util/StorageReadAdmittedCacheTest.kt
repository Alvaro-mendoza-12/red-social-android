package conectate.com.util
import android.net.Uri
import androidx.media3.datasource.*
import conectate.com.core.media.*
import conectate.com.data.localfallback.UbuntuStorageUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StorageReadAdmittedCacheTest {
    private val spec get()=DataSpec.Builder().setUri(UbuntuStorageUrl.baseUrl+"/storage/v1/object/public/videos/owner/test.mp4").build()
    private val a=StorageReadCredential(StorageReadIdentity("a",1),"test.jwt")
    private class Source(val afterRead: () -> Unit = {}) : DataSource {
        var opens=0;var key:String?=null
        override fun open(spec:DataSpec):Long {opens++;key=spec.key;return 1}
        override fun read(b:ByteArray,o:Int,n:Int):Int {b[o]=42;afterRead();return 1}
        override fun close()=Unit
        override fun getUri():Uri?=null
        override fun addTransferListener(l:TransferListener)=Unit
    }
    @Test fun cachedBytesRequireAFreshAdmissionOnEveryOpen() {
        StorageReadSession.install { a }
        try {val upstream=Source();val cache=Source();var checks=0
            val ds=StorageReadDataSource(DataSource.Factory{upstream},DataSource.Factory{cache}){checks++;"scope-revision"}
            repeat(2){ds.open(spec);ds.close()};assertEquals(2,checks);assertEquals(2,cache.opens);assertEquals(0,upstream.opens);assertEquals("scope-revision",cache.key)
        }finally{StorageReadSession.install {null}}
    }
    @Test fun missingOptInNeverReusesCachedPrivateBytes() {
        StorageReadSession.install {a}
        try {val upstream=Source();val cache=Source();val ds=StorageReadDataSource(DataSource.Factory{upstream},DataSource.Factory{cache}){null}
            ds.open(spec);ds.close();assertEquals(1,upstream.opens);assertEquals(0,cache.opens)
        }finally{StorageReadSession.install {null}}
    }
    @Test fun revokedPermissionThrowsBeforeCachedBytesAreOpened() {
        StorageReadSession.install {a}
        try {val cache=Source();val ds=StorageReadDataSource(DataSource.Factory{Source()},DataSource.Factory{cache}){throw IOException("denied")}
            assertThrows(IOException::class.java){ds.open(spec)};assertEquals(0,cache.opens);ds.close()
        }finally{StorageReadSession.install {null}}
    }
    @Test fun changingAccountDuringAdmissionCannotOpenTheCache() {
        var credential=a;StorageReadSession.install{credential}
        try {val cache=Source();val ds=StorageReadDataSource(DataSource.Factory{Source()},DataSource.Factory{cache}){credential=StorageReadCredential(StorageReadIdentity("b",2),"other.jwt");"revision"}
            assertThrows(IOException::class.java){ds.open(spec)};assertEquals(0,cache.opens);ds.close()
        }finally{StorageReadSession.install{null}}
    }
    @Test fun changingAccountAfterOpenCannotReturnABufferedByte() {
        var credential=a;StorageReadSession.install{credential}
        try {val ds=StorageReadDataSource(DataSource.Factory{Source()},DataSource.Factory{Source()}){"revision"};ds.open(spec)
            credential=StorageReadCredential(StorageReadIdentity("b",2),"other.jwt");val bytes=ByteArray(1)
            assertThrows(IOException::class.java){ds.read(bytes,0,1)};assertEquals(0,bytes[0].toInt());ds.close()
        }finally{StorageReadSession.install{null}}
    }

    @Test fun speculativePrivateReadWithoutAdmissionDoesNotDownloadUnusableBytes() {
        StorageReadSession.install { a }
        try {
            val upstream = Source(); val cache = Source()
            val ds = StorageReadDataSource(DataSource.Factory { upstream }, DataSource.Factory { cache }, true) { null }
            assertThrows(IOException::class.java) { ds.open(spec) }
            assertEquals(0, upstream.opens); assertEquals(0, cache.opens)
            ds.close()
        } finally { StorageReadSession.install { null } }
    }

    @Test fun speculativePrivateReadUsesTheSameAdmittedRevisionAsVisiblePlayback() {
        StorageReadSession.install { a }
        try {
            val cache = Source()
            val ds = StorageReadDataSource(DataSource.Factory { Source() }, DataSource.Factory { cache }, true) { "account-and-etag" }
            ds.open(spec); ds.close()
            assertEquals("account-and-etag", cache.key)
            assertEquals(1, cache.opens)
        } finally { StorageReadSession.install { null } }
    }

    @Test fun cancellationAndLogoutDuringABlockedReadCannotPublishBufferedBytes() {
        StorageReadSession.install { a }
        lateinit var ds: StorageReadDataSource
        val upstream = Source {
            StorageReadSession.install { null }
            ds.close()
        }
        ds = StorageReadDataSource(DataSource.Factory { upstream }, DataSource.Factory { Source() })
        val destination = ByteArray(8)
        try {
            ds.open(spec)
            assertThrows(IOException::class.java) { ds.read(destination, 0, destination.size) }
            assertArrayEquals(ByteArray(8), destination)
        } finally { ds.close(); StorageReadSession.install { null } }
    }

    @Test fun streamingOnlyGuestDoesNotPayForAHeadThatCannotEnableItsCache() {
        StorageReadSession.installGuest(
            credentials = { StorageReadCredential(StorageReadIdentity("guest_probe", 1), "guest.jwt") },
            active = { true },
        )
        try {
            assertNull(PlaybackCacheAdmission.key(spec, sourceFactory = DataSource.Factory {
                error("Guest playback must go directly to its authorized streaming GET")
            }))
        } finally { StorageReadSession.installGuest(credentials = { null }) }
    }
}
