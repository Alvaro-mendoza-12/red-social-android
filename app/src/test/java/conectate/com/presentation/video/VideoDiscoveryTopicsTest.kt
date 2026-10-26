package conectate.com.presentation.video

import conectate.com.data.model.Video
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VideoDiscoveryTopicsTest {
    @Test fun usesVisibleCurrentMonthTopicsAndNormalizesAccents() {
        val topics = videoDiscoveryTopics(listOf(
            Video(id = "1", tags = listOf("#Música", "@correo.gmail.com"), views = 12, created_at = "2026-09-20T00:00:00Z"),
            Video(id = "2", tags = listOf("musica"), views = 4, created_at = "2026-09-21T00:00:00Z"),
            Video(id = "3", tags = listOf("privado"), isPublic = false),
            Video(id = "4", tags = listOf("antiguo"), created_at = "2026-08-01T00:00:00Z"),
            Video(id = "5", tags = listOf("sin fecha")),
            Video(id = "6", tags = listOf("futuro"), created_at = "2026-10-01T00:00:00Z")), Instant.parse("2026-09-30T12:00:00Z"))
        assertEquals(1, topics.size)
        assertEquals("musica", normalizedVideoSearch(topics.first().query))
        assertEquals("Tema del mes", topics.first().label)
        assertNull(videoRelatedTopic(Video(title = "Correo @privado", tags = listOf("@privado"))))
    }

    @Test fun contextualSearchRequiresSpecificCorroboratedPublicMetadata() {
        assertNull(videoRelatedTopic(Video(title = "Mi nuevo video", tags = listOf("viral", "fyp"))))
        assertNull(videoRelatedTopic(Video(title = "Momento cualquiera", tags = listOf("Construcción"))))
        assertNull(videoRelatedTopic(Video(title = "Construcción de casas")))
        assertEquals("Construcción", videoRelatedTopic(Video(title = "Construcción de casas",
            tags = listOf("viral", "Construcción"))))
        assertEquals("Construcción de casas", videoRelatedTopic(Video(title = "Construcción de casas",
            description = "Aprende construcción de casas modernas")))
        assertNull(videoRelatedTopic(Video(title = "Construcción de casas", tags = listOf("Construcción"), isPublic = false)))
        assertNull(videoRelatedTopic(Video(title = "Construcción de casas", tags = listOf("Construcción")).apply { isSensitive = true }))
    }
}
