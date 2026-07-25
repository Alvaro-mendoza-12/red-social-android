package conectate.com.presentation.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionQrParserTest {
    private val roomId = "2af71466-e8bb-4fd0-87e1-2bb669b30819"

    @Test
    fun parsesSharedGroupLinkAndInvite() {
        val target = CollectionQrParser.parse(
            "https://conectate.com/group/$roomId?invite=abc%2D123"
        ).getOrThrow()

        assertEquals("group", target.kind)
        assertEquals(roomId, target.roomId)
        assertEquals("abc-123", target.inviteToken)
    }

    @Test
    fun parsesCustomCommunityLink() {
        val target = CollectionQrParser.parse(
            "conectate://community/$roomId"
        ).getOrThrow()

        assertEquals("community", target.kind)
        assertEquals(roomId, target.roomId)
        assertEquals(null, target.inviteToken)
    }

    @Test
    fun rejectsForeignQrLinks() {
        val result = CollectionQrParser.parse(
            "https://example.com/group/$roomId?invite=abc"
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no pertenece"))
    }

    @Test
    fun rejectsGroupLinkWithoutInvite() {
        val result = CollectionQrParser.parse("https://conectate.com/group/$roomId")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("incompleta"))
    }
}
