package conectate.com.core.media

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProfileIdentityFilesTest {
    @Test fun `more than 96 account images survive generic LRU pruning and restart`() = temporary { root ->
        val store = ProfileIdentityFiles(root)
        repeat(130) { assertNotNull(store.write("a", "avatar-$it", byteArrayOf(1))) }
        repeat(100) { assertNotNull(store.write(null, "other-$it", byteArrayOf(2))) }
        ProfileIdentityImageStore.prune(root, maxImages = 2, maxTotalBytes = 100)
        val reopened = ProfileIdentityFiles(root)
        repeat(130) { assertNotNull(reopened.find("a", "avatar-$it")) }
        assertEquals(130, reopened.usage("a")!!.imageCount)
        assertEquals(2, root.listFiles().orEmpty().count { it.extension == "webp" })
    }

    @Test fun `accounts are isolated including same source and normalized account identity`() = temporary { root ->
        val store = ProfileIdentityFiles(root)
        val first = store.write(" A ", "same-source", byteArrayOf(1))!!
        assertEquals(first, store.find("a", "same-source"))
        assertNull(store.find("b", "same-source"))
        assertNull(store.find(null, "same-source"))
        val second = store.write("b", "same-source", byteArrayOf(2))!!
        assertNotEquals(first, second)
        assertArrayEquals(byteArrayOf(1), first.readBytes())
        assertArrayEquals(byteArrayOf(2), second.readBytes())
        assertTrue(first.name.matches(Regex("[0-9a-f]{64}\\.webp")))
        assertTrue(requireNotNull(first.parentFile).name.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun `blank account never falls back to anonymous persistence`() = temporary { root ->
        val store = ProfileIdentityFiles(root)
        assertNotNull(store.write(null, "source", byteArrayOf(1)))
        assertNull(store.find(" ", "source"))
        assertNull(store.write(" ", "source", byteArrayOf(2)))
        assertNull(store.usage(""))
    }

    @Test fun `storage reserve rejects new copy without evicting old identities`() = temporary { root ->
        val normal = ProfileIdentityFiles(root)
        val kept = normal.write("a", "kept", byteArrayOf(1))!!
        val full = ProfileIdentityFiles(root, availableBytes = { 64 })
        assertNull(full.write("a", "new", byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(1), kept.readBytes())
    }

    @Test fun `failed atomic replacement keeps old complete image and removes only temp`() = temporary { root ->
        val normal = ProfileIdentityFiles(root)
        val kept = normal.write("a", "same", byteArrayOf(1))!!
        val failing = ProfileIdentityFiles(root, publish = { _, _ -> false })
        assertNull(failing.write("a", "same", byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(1), kept.readBytes())
        assertTrue(requireNotNull(kept.parentFile).listFiles().orEmpty().none { it.extension == "tmp" })
    }

    @Test fun `empty and oversized encoded images never reach disk`() = temporary { root ->
        val store = ProfileIdentityFiles(root)
        assertNull(store.write("a", "empty", byteArrayOf()))
        assertNull(store.write("a", "oversized", ByteArray(ProfileIdentityFiles.MAX_ENTRY_BYTES + 1)))
        assertEquals(0, store.usage("a")!!.imageCount)
    }

    @Test fun `confirmed clear removes only the chosen account and blocks legacy resurrection`() = temporary { root ->
        val store = ProfileIdentityFiles(root)
        val own = store.write("a", "source", byteArrayOf(1))!!
        val other = store.write("b", "source", byteArrayOf(2))!!
        val legacy = store.write(null, "source", byteArrayOf(3))!!
        val unrelated = File(own.parentFile, "not-an-identity.txt").apply { writeText("keep") }
        val confirmed = store.usage("a")!!
        assertFalse(store.clear("b", confirmed.accountKey))
        assertTrue(own.exists())
        assertTrue(other.exists())
        assertTrue(store.canMigrateLegacy("a"))
        assertTrue(store.clear("a", confirmed.accountKey))
        assertFalse(own.exists())
        assertTrue(other.exists())
        assertTrue(legacy.exists())
        assertTrue(unrelated.exists())
        assertFalse(ProfileIdentityFiles(root).canMigrateLegacy("a"))
        assertTrue(store.canMigrateLegacy("b"))
        assertEquals(0, store.usage("a")!!.imageCount)
    }

    @Test fun `usage counts only that account and paths never include raw identifiers`() = temporary { root ->
        val store = ProfileIdentityFiles(root)
        val source = "https://example.invalid/private?token=not-a-real-token"
        val file = store.write("../../outside", source, ByteArray(20))!!
        store.write("b", source, ByteArray(50))
        assertTrue(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertFalse(file.path.contains("token="))
        assertFalse(requireNotNull(file.parentFile).name.contains("outside"))
        assertEquals(20L, store.usage("../../outside")!!.bytes)
    }

    @Test fun `identity images are bounded without enlarging smaller avatars`() {
        assertEquals(512 to 256, profileIdentityDimensions(4096, 2048))
        assertEquals(256 to 512, profileIdentityDimensions(2048, 4096))
        assertEquals(48 to 48, profileIdentityDimensions(48, 48))
        assertEquals(1 to 1, profileIdentityDimensions(0, 0))
    }

    @Test fun `video warmup is rejected even with query parameters or uppercase extension`() {
        assertFalse(isStaticProfileIdentitySource("https://example.invalid/avatar.MP4?width=384"))
        assertFalse(isStaticProfileIdentitySource("https://example.invalid/avatar.webm#frame"))
        assertTrue(isStaticProfileIdentitySource("https://example.invalid/avatar.webp?width=384"))
    }

    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("novvin-identity-files-test-").toFile()
        try { block(root) } finally {
            assertTrue(root.name.startsWith("novvin-identity-files-test-"))
            assertTrue(root.deleteRecursively())
        }
    }
}
