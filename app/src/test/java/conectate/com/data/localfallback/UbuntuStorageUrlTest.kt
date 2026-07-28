package conectate.com.data.localfallback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UbuntuStorageUrlTest {

    @Test
    fun `storage cache key survives host and profile version changes`() {
        val first = "https://old.example/storage/v1/object/public/avatars/User-1/avatar.jpg?v=1"
        val second = "https://new.example/storage/v1/object/public/avatars/User-1/avatar.jpg?v=2#preview"

        assertEquals(UbuntuStorageUrl.stableCacheKey(first), UbuntuStorageUrl.stableCacheKey(second))
        assertEquals(
            "ubuntu-storage:/storage/v1/object/public/avatars/User-1/avatar.jpg",
            UbuntuStorageUrl.stableCacheKey(first)
        )
    }

    @Test
    fun `different storage objects retain different cache keys`() {
        assertNotEquals(
            UbuntuStorageUrl.stableCacheKey("https://example/storage/v1/object/public/avatars/u/a.jpg?v=1"),
            UbuntuStorageUrl.stableCacheKey("https://example/storage/v1/object/public/avatars/u/b.jpg?v=1")
        )
        assertNull(UbuntuStorageUrl.stableCacheKey("   "))
    }

    @Test
    fun `cache key ignores signed tokens but preserves image variants`() {
        val first = "https://old.example/storage/v1/object/sign/chat-media/user/photo.jpg?token=one&width=720&quality=78&format=webp"
        val second = "https://new.example/storage/v1/object/public/chat-media/user/photo.jpg?format=webp&quality=78&width=720&v=9"

        assertEquals(UbuntuStorageUrl.stableCacheKey(first), UbuntuStorageUrl.stableCacheKey(second))
        assertNotEquals(
            UbuntuStorageUrl.stableCacheKey(second),
            UbuntuStorageUrl.stableCacheKey(second.replace("width=720", "width=1080"))
        )
    }

    @Test
    fun `live replay storage references retain a stable cache identity`() {
        val first = "https://old.example/storage/v1/object/public/live-replays/user/replay.mp4?v=1"
        val second = "https://new.example/storage/v1/object/public/live-replays/user/replay.mp4?v=2"

        assertEquals(UbuntuStorageUrl.stableCacheKey(first), UbuntuStorageUrl.stableCacheKey(second))
        assertEquals(
            "ubuntu-storage:/storage/v1/object/public/live-replays/user/replay.mp4",
            UbuntuStorageUrl.stableCacheKey(first)
        )
    }
}
