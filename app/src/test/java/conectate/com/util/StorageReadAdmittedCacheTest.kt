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
    private class Source : DataSource {
        var opens=0;var key:String?=null
        override fun open(spec:DataSpec):Long {opens++;key=spec.key;return 1}
        override fun read(b:ByteArray,o:Int,n:Int):Int {b[o]=42;return 1}
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
}
