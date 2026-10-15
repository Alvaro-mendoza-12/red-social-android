package conectate.com.util
import conectate.com.core.media.StorageReadCredential
import conectate.com.core.media.StorageReadIdentity
import conectate.com.core.media.StorageReadSession
import conectate.com.data.localfallback.UbuntuStorageUrl
import org.junit.Assert.*
import org.junit.Test
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackCacheAdmissionTest {
    private val url get() = UbuntuStorageUrl.baseUrl + "/storage/v1/object/public/videos/owner/test.mp4"
    @Test fun admissionAndRevisionAreRequiredAndAudioIsExplicit() {
        assertNull(PlaybackCacheAdmission.revisionKey(url,"key",null,"etag","video/mp4",false))
        assertNull(PlaybackCacheAdmission.revisionKey(url,"key","permission-check-v1",null,"video/mp4",false))
        assertNull(PlaybackCacheAdmission.revisionKey(url,"key","permission-check-v1","etag","audio/aac",false))
        assertNotNull(PlaybackCacheAdmission.revisionKey(url,"key","permission-check-v1","etag","audio/aac",true))
    }
    @Test fun revisionsAndAccountsCannotReadEachOthersCachedPrefix() {
        var credential=StorageReadCredential(StorageReadIdentity("account-a",1),"test.token")
        StorageReadSession.install { credential }
        try {
            fun key(revision: String) = PlaybackCacheAdmission.revisionKey(url,"key","permission-check-v1",revision,"video/mp4",false)
            val a=key("one");assertNotEquals(a,key("two"))
            credential=StorageReadCredential(StorageReadIdentity("account-b",2),"test.token")
            assertNotEquals(a,key("one"))
        } finally { StorageReadSession.install { null } }
    }
}
