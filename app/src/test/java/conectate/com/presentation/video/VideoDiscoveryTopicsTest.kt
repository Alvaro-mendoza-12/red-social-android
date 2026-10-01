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
            Video(id = "4", tags = listOf("antiguo"), created_at = "2026-08-01T00:00:00Z")), Instant.parse("2026-09-30T12:00:00Z"))
        assertEquals(1, topics.size)
        assertEquals("musica", normalizedVideoSearch(topics.first().query))
        assertEquals("Tema del mes", topics.first().label)
        assertNull(videoRelatedTopic(Video(title = "Correo @privado", tags = listOf("@privado"))))
    }
}
