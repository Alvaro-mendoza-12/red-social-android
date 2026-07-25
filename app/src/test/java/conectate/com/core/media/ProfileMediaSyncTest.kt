package conectate.com.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMediaSyncTest {

    @Test
    fun `canonical storage identity survives host changes and cache versions`() {
        val uploaded =
            "https://project.supabase.co/storage/v1/object/public/avatars/user-1/avatar%20final.jpg"
        val reopened =
            "https://media.example.com/storage/v1/object/public/avatars/user-1/avatar%20final.jpg?v=42"

        assertTrue(ProfileMediaSync.sameReference(uploaded, reopened))
        assertEquals(
            "storage:avatars/user-1/avatar final.jpg",
            ProfileMediaSync.canonicalReference(reopened)
        )
    }

    @Test
    fun `public url uses server assigned owner path`() {
        assertEquals(
            "owner-7/user-1/avatar.mp4",
            ProfileMediaSync.uploadedObjectPath(
                bucket = "avatars",
                requestedPath = "user-1/avatar.mp4",
                responseKey = "avatars/owner-7/user-1/avatar.mp4",
                responseName = null
            )
        )
    }

    @Test
    fun `profile update is not confirmed when server returns previous media`() {
        val mutation = ProfileMediaMutation(
            avatarUrl = "https://media.example.com/storage/v1/object/public/avatars/user-1/new.mp4",
            coverUrl = "https://media.example.com/storage/v1/object/public/avatars/user-1/new-cover.jpg"
        )

        val failure = ProfileMediaSync.confirmationFailure(
            mutation = mutation,
            actualAvatarUrl = "https://media.example.com/storage/v1/object/public/avatars/user-1/old.jpg",
            actualCoverUrl = mutation.coverUrl
        )

        assertEquals(ProfileMediaField.AVATAR, failure?.field)
        assertFalse(failure?.removalExpected ?: true)
    }

    @Test
    fun `versioned server media confirms persistence and old objects become cleanup candidates`() {
        val mutation = ProfileMediaMutation(
            avatarUrl = "https://old-host/storage/v1/object/public/avatars/user-1/new.mp4",
            coverUrl = "https://old-host/storage/v1/object/public/avatars/user-1/new-cover.jpg"
        )
        val confirmedAvatar =
            "https://current-host/storage/v1/object/public/avatars/user-1/new.mp4?v=20260719"
        val confirmedCover =
            "https://current-host/storage/v1/object/public/avatars/user-1/new-cover.jpg"

        assertNull(
            ProfileMediaSync.confirmationFailure(
                mutation,
                actualAvatarUrl = confirmedAvatar,
                actualCoverUrl = confirmedCover
            )
        )
        assertEquals(
            listOf(
                "https://old-host/storage/v1/object/public/avatars/user-1/old.jpg",
                "https://old-host/storage/v1/object/public/avatars/user-1/old-cover.jpg"
            ),
            ProfileMediaSync.replacedUrls(
                mutation = mutation,
                previousAvatarUrl = "https://old-host/storage/v1/object/public/avatars/user-1/old.jpg",
                previousCoverUrl = "https://old-host/storage/v1/object/public/avatars/user-1/old-cover.jpg",
                confirmedAvatarUrl = confirmedAvatar,
                confirmedCoverUrl = confirmedCover
            )
        )
    }

    @Test
    fun `failed or unrelated save retains previous media`() {
        val unrelatedMutation = ProfileMediaMutation()

        assertTrue(
            ProfileMediaSync.replacedUrls(
                mutation = unrelatedMutation,
                previousAvatarUrl = "avatars/user-1/avatar.jpg",
                previousCoverUrl = "avatars/user-1/cover.jpg",
                confirmedAvatarUrl = null,
                confirmedCoverUrl = null
            ).isEmpty()
        )
    }

    @Test
    fun `removal requires an absent value in the persisted profile`() {
        val mutation = ProfileMediaMutation(removeAvatar = true, removeCover = true)

        assertEquals(
            ProfileMediaField.AVATAR,
            ProfileMediaSync.confirmationFailure(
                mutation,
                actualAvatarUrl = "avatars/user-1/avatar.jpg",
                actualCoverUrl = null
            )?.field
        )
        assertNull(
            ProfileMediaSync.confirmationFailure(
                mutation,
                actualAvatarUrl = null,
                actualCoverUrl = "null"
            )
        )
    }

    @Test
    fun `remote read started before commit cannot overwrite committed state`() {
        val clock = ProfileMutationClock()
        val staleRead = clock.snapshot()

        clock.markCommitted()

        assertFalse(clock.isCurrent(staleRead))
        assertTrue(clock.isCurrent(clock.snapshot()))
    }
}
