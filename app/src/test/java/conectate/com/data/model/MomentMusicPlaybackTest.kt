package conectate.com.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentMusicPlaybackTest {
    private fun moment(embedded: Boolean) = Moment(
        id = "moment",
        userId = "owner",
        mediaUrl = "https://novvin.example/moment.mp4",
        mediaType = "video",
        musicAudioUrl = "https://upload.wikimedia.org/music.ogg",
        musicEmbedded = embedded,
    )

    @Test
    fun `legacy or image publication keeps separate soundtrack player`() {
        assertTrue(moment(embedded = false).requiresSeparateMusicPlayback)
    }

    @Test
    fun `flattened media never starts a duplicate soundtrack player`() {
        assertFalse(moment(embedded = true).requiresSeparateMusicPlayback)
    }
}
