package conectate.com.data.repository

import conectate.com.data.model.VoiceRoom
import org.junit.Assert.*
import org.junit.Test

class VoiceRoomDeletionRollbackTest {
    private val removed = VoiceRoom(id = "room", name = "Sala anterior")
    @Test fun failedDeletionPreservesRoomsReceivedWhileTheCommandWasPending() {
        val newer = VoiceRoom(id = "new", name = "Sala nueva")
        assertEquals(setOf("new", "room"), restoreFailedVoiceRoomDeletion(listOf(newer), removed, "owner", "owner").map { it.id }.toSet())
    }
    @Test fun anAcknowledgementFromAnotherAccountCannotRestorePrivateRoomMetadata() {
        val current = listOf(VoiceRoom(id = "other-account"))
        assertSame(current, restoreFailedVoiceRoomDeletion(current, removed, "owner", "other"))
        assertSame(current, restoreFailedVoiceRoomDeletion(current, removed, "owner", null))
    }
    @Test fun rollbackCannotOverwriteANewerVersionOrDuplicateAnExistingRoom() {
        val newer = removed.copy(name = "Nombre actualizado")
        assertSame(newer, restoreFailedVoiceRoomDeletion(listOf(newer), removed, "owner", "owner").single())
        assertTrue(restoreFailedVoiceRoomDeletion(emptyList(), null, "owner", "owner").isEmpty())
    }
}
