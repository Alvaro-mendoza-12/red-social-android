package conectate.com.util

import conectate.com.core.media.StorageReadCredential
import conectate.com.core.media.StorageReadIdentity
import conectate.com.core.media.StorageReadSession
import conectate.com.data.localfallback.UbuntuStorageUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoCacheResourceKeysTest {
    private val url get() = UbuntuStorageUrl.baseUrl + "/storage/v1/object/public/videos/owner/failed.mp4"
    private fun credential(account: String, generation: Long = 1) =
        StorageReadCredential(StorageReadIdentity(account, generation), "synthetic.token")

    @After fun reset() { StorageReadSession.install { null } }

    @Test fun recoveryEvictsAllCurrentRevisionsButNotOtherAccountsOrObjects() {
        var current = credential("account-a")
        StorageReadSession.install { current }
        val stable = requireNotNull(UbuntuStorageUrl.stableCacheKey(url))
        fun revision(etag: String) = requireNotNull(PlaybackCacheAdmission.revisionKey(
            url, stable, "permission-check-v1", etag, "video/mp4", false))
        val first = revision("first")
        val second = revision("second")
        val otherObject = StorageReadSession.scopedCacheKey(url, "$stable-other:revision:abc")
        current = credential("account-b")
        val otherAccount = revision("first")
        current = credential("account-a", 2)
        val newLogin = revision("first")
        current = credential("account-a")
        val keys = setOf(stable, first, second, otherObject, otherAccount, newLogin)
        assertEquals(setOf(stable, first, second), videoCacheKeysToRemove(url, keys))
    }

    @Test fun signedUrlRenewalStillFindsTheSameFailedObject() {
        StorageReadSession.install { credential("account-a") }
        val stable = requireNotNull(UbuntuStorageUrl.stableCacheKey(url))
        val revision = StorageReadSession.scopedCacheKey(url, "$stable:revision:old")
        assertEquals(setOf(revision), videoCacheKeysToRemove("$url?token=renewed", setOf(revision)))
    }

    @Test fun externalRecoveryOnlyEvictsItsOwnResource() {
        val external = "https://cdn.example.test/video.mp4"
        val stable = requireNotNull(UbuntuStorageUrl.stableCacheKey(external))
        assertEquals(setOf(stable), videoCacheKeysToRemove(external, setOf(stable, "$stable-other", "unrelated")))
    }

    @Test fun logoutCannotEvictThePreviousMembersRevision() {
        StorageReadSession.install { credential("account-a") }
        val stable = requireNotNull(UbuntuStorageUrl.stableCacheKey(url))
        val revision = StorageReadSession.scopedCacheKey(url, "$stable:revision:old")
        StorageReadSession.install { null }
        assertTrue(videoCacheKeysToRemove(url, setOf(revision)).isEmpty())
    }
}
