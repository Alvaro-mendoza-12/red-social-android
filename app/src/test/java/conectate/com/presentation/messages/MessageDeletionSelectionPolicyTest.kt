package conectate.com.presentation.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeletionSelectionPolicyTest {
    private fun candidate(id: String, sender: String = "me", deleted: Boolean = false) =
        MessageDeletionPolicy.Candidate(id, sender, deleted)

    @Test fun ownCompleteSelectionIsAllowed() {
        assertTrue(MessageDeletionPolicy.canDeleteForEveryone("me", setOf("a", "b"),
            listOf(candidate("a"), candidate("b"))))
    }

    @Test fun mixedSelectionIsRejectedAsAWhole() {
        assertFalse(MessageDeletionPolicy.canDeleteForEveryone("me", setOf("a", "b"),
            listOf(candidate("a"), candidate("b", "other"))))
    }

    @Test fun missingOrAlreadyDeletedMessageCannotBeAuthorizedLocally() {
        assertFalse(MessageDeletionPolicy.canDeleteForEveryone("me", setOf("a", "missing"), listOf(candidate("a"))))
        assertFalse(MessageDeletionPolicy.canDeleteForEveryone("me", setOf("a"), listOf(candidate("a", deleted = true))))
    }

    @Test fun emptyOrUnauthenticatedSelectionIsRejected() {
        assertFalse(MessageDeletionPolicy.canDeleteForEveryone(null, setOf("a"), listOf(candidate("a"))))
        assertFalse(MessageDeletionPolicy.canDeleteForEveryone("me", emptySet(), listOf(candidate("a"))))
        assertFalse(MessageDeletionPolicy.canDeleteForEveryone("me", setOf(" "), listOf(candidate(" "))))
    }

    @Test fun identityNormalizationDoesNotAuthorizeAnotherSender() {
        assertTrue(MessageDeletionPolicy.canDeleteForEveryone(" ME ", setOf(" A "), listOf(candidate("a", "Me"))))
        assertFalse(MessageDeletionPolicy.canDeleteForEveryone(" ME ", setOf(" A "), listOf(candidate("a", "Me-other"))))
    }
}
