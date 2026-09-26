package conectate.com.presentation.components

import conectate.com.data.model.Moment
import org.junit.Assert.assertEquals
import org.junit.Test

class MyMomentComponentsTest {
    private fun moment(
        visibility: String = "all",
        visibilityType: String = "public",
        audienceIds: List<String> = emptyList(),
        excludedIds: List<String> = emptyList(),
    ) = Moment(
        id = "moment-1",
        userId = "owner-1",
        mediaUrl = "https://example.com/moment.jpg",
        mediaType = "image",
        visibility = visibility,
        visibilityType = visibilityType,
        audienceIds = audienceIds,
        excludedIds = excludedIds,
    )

    @Test
    fun `shows the real audience instead of a fixed public label`() {
        assertEquals("Visible para todos", momentAudienceLabel(moment()))
        assertEquals("Solo tú", momentAudienceLabel(moment(visibility = "only_me", visibilityType = "private")))
        assertEquals("Quienes te siguen", momentAudienceLabel(moment(visibility = "followers")))
        assertEquals("Personas que sigues", momentAudienceLabel(moment(visibility = "following")))
        assertEquals("Personas con las que hablas", momentAudienceLabel(moment(visibility = "chats")))
        assertEquals(
            "2 personas elegidas",
            momentAudienceLabel(moment(visibility = "only_selected", audienceIds = listOf("a", "b"))),
        )
        assertEquals(
            "Todos excepto 1",
            momentAudienceLabel(moment(visibility = "except_selected", excludedIds = listOf("a"))),
        )
    }

    @Test
    fun `video cards use the playable media instead of a still thumbnail`() {
        val video = moment().copy(
            mediaUrl = "https://cdn.example.com/moment.mp4",
            mediaType = "video",
            thumbnailUrl = "https://cdn.example.com/moment.jpg",
        )
        val image = moment().copy(
            mediaUrl = "https://cdn.example.com/photo.jpg",
            mediaType = "image",
            thumbnailUrl = "https://cdn.example.com/photo-thumb.jpg",
        )

        assertEquals("https://cdn.example.com/moment.mp4", video.cardPreviewUrl())
        assertEquals("https://cdn.example.com/photo-thumb.jpg", image.cardPreviewUrl())
    }

    @Test
    fun `owner card resolves the newest active moment instead of creation`() {
        val old = moment().copy(id = "old", createdAt = "2026-08-20T10:00:00Z")
        val newest = moment().copy(id = "new", createdAt = "2026-08-21T10:00:00Z")

        assertEquals("new", primaryOwnerMoment(listOf(old, newest))?.id)
        assertEquals(null, primaryOwnerMoment(emptyList()))
    }
}
