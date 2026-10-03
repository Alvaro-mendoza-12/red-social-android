package conectate.com.data.local

import conectate.com.data.local.entity.UserEntity
import conectate.com.data.model.User
import conectate.com.data.model.profilePublicHandle
import conectate.com.data.model.profileShortId
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class UserProfileCacheTest {
    private val user = User(id = "qa-profile", full_name = "Mirella", display_name = "Mirella Vásquez",
        username = "mirella_vr07", username_origin = "chosen", numeric_id = 12345,
        email = "mirella_vr07@example.invalid", favorite_music_url = "https://example.invalid/song.mp3",
        profile_view_history_enabled = false, is_private = true, profile_visibility = "friends")

    @Test fun preservesAuthoritativeHandleDisplayNameAndNumericId() {
        val cached = UserEntity.fromDomain(user).toDomain()
        assertEquals("mirella_vr07", profilePublicHandle(cached))
        assertEquals(user.displayName, cached.displayName)
        assertEquals("12345", profileShortId(cached))
    }
    @Test fun preservesMusicAndPrivacyBeforeTheRemoteRefresh() {
        val cached = UserEntity.fromDomain(user).toDomain()
        assertEquals(user.favorite_music_url, cached.favorite_music_url)
        assertFalse(cached.profile_view_history_enabled)
        assertTrue(cached.is_private)
        assertEquals("friends", cached.profile_visibility)
    }
    @Test fun legacyRowsNeverTreatAnEmailDerivedUsernameAsAuthoritative() {
        val cached = UserEntity.fromDomain(user).copy(profile_json = null).toDomain()
        assertNull(cached.username_origin)
        assertEquals("mirella", profilePublicHandle(cached))
    }
    @Test fun corruptedOrWrongAccountSnapshotsFallBackToCompactColumns() {
        val entity = UserEntity.fromDomain(user)
        val wrongAccount = UserEntity.fromDomain(user.copy(id = "someone-else")).profile_json
        listOf("broken-json", wrongAccount).forEach { raw ->
            val cached = entity.copy(profile_json = raw).toDomain()
            assertEquals(user.id, cached.id)
            assertNull(cached.username_origin)
            assertEquals("mirella", profilePublicHandle(cached))
            assertNull(cached.favorite_music_url)
        }
    }
    @Test fun presenceAndCountsComeFromTheirCurrentDatabaseColumns() {
        val cached = UserEntity.fromDomain(user).copy(is_online = true, follower_count = 91,
            following_count = 42, last_seen = "2026-10-03T10:00:00Z").toDomain()
        assertTrue(cached.is_online)
        assertEquals(91, cached.followers_count)
        assertEquals(42, cached.following_count)
        assertEquals("2026-10-03T10:00:00Z", cached.lastSeen)
        assertEquals("mirella_vr07", profilePublicHandle(cached))
    }
    @Test fun changedCompactUsernameDoesNotInheritOldAuthoritativeProvenance() {
        val cached = UserEntity.fromDomain(user).copy(username = "legacy.emailprefix").toDomain()
        assertNull(cached.username_origin)
        assertEquals("mirella", profilePublicHandle(cached))
    }
}
