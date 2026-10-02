package conectate.com.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class QuotedMessageAuthorTest {
    @Test fun ownMessageIsNamedYouEvenWhenAnotherUserReplies() {
        assertEquals("Tú", quotedMessageAuthor("me", "me", "My name", "other", "Other name"))
    }
    @Test fun anotherPersonsSelfReplyIsNotNamedYou() {
        assertEquals("Other name", quotedMessageAuthor("other", "me", null, "other", "Other name"))
    }
    @Test fun outgoingReplyUsesTheQuotedPersonsName() {
        assertEquals("QA Tablet", quotedMessageAuthor("other", "me", "QA Tablet", "me", null))
    }
    @Test fun thirdGroupMemberHasTheirOwnName() {
        assertEquals("Third member", quotedMessageAuthor("third", "me", "Third member", "other", "Other name"))
    }
    @Test fun unknownOrBlankIdentityIsNotFalselyNamedYou() {
        assertEquals("Mensaje citado", quotedMessageAuthor("", "", null, "", null))
        assertEquals("Mensaje citado", quotedMessageAuthor("third", "me", null, "other", "Other name"))
    }
    @Test fun identifiersAreComparedWithoutCaseOrSurroundingWhitespace() {
        assertEquals("Tú", quotedMessageAuthor(" ME ", "me", null, "other", "Other"))
    }
}
