package conectate.com.core.media

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileIdentityImageStoreTest {

    @Test
    fun `identity image survives a public origin rotation`() {
        withTemporaryDirectory { directory ->
            val first = ProfileIdentityImageStore.imageFile(
                directory,
                "https://old.example.test/storage/v1/object/public/avatars/person/avatar.mp4",
            )
            val rotated = ProfileIdentityImageStore.imageFile(
                directory,
                "https://new.example.test/storage/v1/object/public/avatars/person/avatar.mp4",
            )

            assertEquals(first?.name, rotated?.name)
            assertTrue(first?.name?.matches(Regex("[0-9a-f]{64}\\.webp")) == true)
        }
    }

    @Test
    fun `pruning keeps only the newest identity images inside its budget`() {
        withTemporaryDirectory { directory ->
            repeat(5) { index ->
                File(directory, "identity-$index.webp").apply {
                    writeBytes(ByteArray(10) { index.toByte() })
                    setLastModified(1_000L + index)
                }
            }
            File(directory, "interrupted.tmp").writeText("partial")

            ProfileIdentityImageStore.prune(directory, maxImages = 2, maxTotalBytes = 100L)

            val remaining = directory.listFiles().orEmpty().map(File::getName).sorted()
            assertEquals(listOf("identity-3.webp", "identity-4.webp"), remaining)
            assertFalse(File(directory, "interrupted.tmp").exists())
        }
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("novvin-profile-identity-test").toFile()
        try {
            block(directory)
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }
}
