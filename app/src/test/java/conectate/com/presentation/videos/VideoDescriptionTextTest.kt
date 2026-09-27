package conectate.com.presentation.videos

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDescriptionTextTest {
    @Test
    fun findsUsernamesWithoutTreatingEmailAsMention() {
        val matches = videoDescriptionMentions("Con @ana.lopez y @juan-p, no correo@dominio.com")
        assertEquals(listOf("ana.lopez", "juan-p"), matches.map { it.username })
    }
}
