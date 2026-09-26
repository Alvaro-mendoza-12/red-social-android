package conectate.com.data.repository

import conectate.com.data.model.Video
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPublishRetryPolicyTest {
    @Test fun `a lost response is recognized only for the same publication details`() {
        val published = Video(
            title = "Título", description = "Descripción", isPublic = true,
            tags = listOf("viaje"), soundId = "sound-1",
        )
        assertTrue(publishedVideoMatchesRetry(
            published, "Título", "Descripción", true, false, listOf("viaje"), "sound-1"
        ))
        assertFalse(publishedVideoMatchesRetry(
            published, "Otro", "Descripción", true, false, listOf("viaje"), "sound-1"
        ))
        assertFalse(publishedVideoMatchesRetry(
            published, "Título", "Descripción", false, false, listOf("viaje"), "sound-1"
        ))
        assertFalse(publishedVideoMatchesRetry(
            published, "Título", "Descripción", true, false, listOf("viaje"), "sound-2"
        ))
    }
}
