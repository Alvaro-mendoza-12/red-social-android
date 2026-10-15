package conectate.com.presentation.components

import org.junit.Assert.*
import org.junit.Test

class AvatarMomentIdentityTest {
    private val owner = "10000000-0000-4000-8000-000000000001"
    @Test fun `explicit profile ID works without a photo`() { assertEquals(owner, avatarMomentOwnerId(owner.uppercase(), null)) }
    @Test fun `only the avatar owner directory can supply identity`() {
        assertEquals(owner, avatarMomentOwnerId(null, "https://example.invalid/storage/v1/object/public/avatars/$owner/file.mp4?x=1"))
        assertNull(avatarMomentOwnerId(null, "https://example.invalid/$owner/file.mp4"))
        assertNull(avatarMomentOwnerId(null, "https://example.invalid/storage/v1/object/public/chat-media/$owner/file.mp4"))
        assertNull(avatarMomentOwnerId(null, "file:///storage/v1/object/public/avatars/$owner/file.mp4"))
    }
    @Test fun `file UUID is not confused with its profile owner`() {
        assertNull(avatarMomentOwnerId(null, "https://example.invalid/storage/v1/object/public/avatars/unknown/$owner.mp4"))
        assertNull(avatarMomentOwnerId("someone@example.invalid", null))
    }
}
