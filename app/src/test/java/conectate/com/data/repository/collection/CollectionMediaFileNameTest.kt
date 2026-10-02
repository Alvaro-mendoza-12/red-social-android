package conectate.com.data.repository.collection

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CollectionMediaFileNameTest {
    @Test fun attachmentsCreatedInTheSameMillisecondKeepDifferentObjectKeys() {
        val names = List(128) { collectionMediaFileName("group_image", "qa-owner", "jpg", 42L) }
        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            assertTrue(name.startsWith("group_image_qa-owner_42_"))
            UUID.fromString(name.removePrefix("group_image_qa-owner_42_").removeSuffix(".jpg"))
        }
    }

    @Test fun extensionsKeepTheirFormatWithoutADoubleDot() {
        listOf("jpg", "mp4", "ogg", "gif", "pdf", ".xlsx").forEach { extension ->
            val name = collectionMediaFileName("community_file", "qa-owner", extension, 0L)
            assertTrue(name.endsWith("." + extension.removePrefix(".")))
            assertFalse(name.contains(".."))
        }
    }
}
