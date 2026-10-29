package conectate.com.data.repository.collection

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionMessageDeletionTest {
    private data class Message(val text: String, val deleted: Boolean = false)
    private val original = Message("original")
    private var stored: Message? = original
    private var sameSession = true
    private val writes = mutableListOf<Message>()

    private suspend fun delete(remote: suspend (Message) -> Result<Unit>) = deleteCollectionMessage(
        isCurrentSession = { sameSession }, load = { stored }, markDeleted = { it.copy(deleted = true) },
        save = { stored = it; writes += it }, remoteDelete = remote,
    )

    @Test fun rejectedDeleteRestoresTheMessageAndReturnsFailure() = runTest {
        val error = IOException("rejected")
        assertSame(error, delete { Result.failure(error) }.exceptionOrNull())
        assertEquals(original, stored)
        assertEquals(listOf(original.copy(deleted = true), original), writes)
    }

    @Test fun thrownTransportFailureAlsoRestoresTheMessage() = runTest {
        assertTrue(delete { throw IOException("offline") }.isFailure)
        assertEquals(original, stored)
    }

    @Test fun rollbackDoesNotReplaceANewerMessageVersion() = runTest {
        val updated = Message("new edit", deleted = true)
        assertTrue(delete { stored = updated; Result.failure(IOException("rejected")) }.isFailure)
        assertEquals(updated, stored)
        assertEquals(1, writes.size)
    }

    @Test fun responseFromPreviousAccountCannotRestoreItsMessage() = runTest {
        try {
            delete { sameSession = false; stored = Message("other account"); Result.failure(IOException()) }
            fail("Account changes must cancel the operation")
        } catch (_: CancellationException) { }
        assertEquals(Message("other account"), stored)
        assertEquals(1, writes.size)
    }

    @Test fun accountChangeDuringInitialReadDoesNotWriteAnything() = runTest {
        try {
            deleteCollectionMessage(isCurrentSession = { sameSession },
                load = { sameSession = false; original }, markDeleted = { it.copy(deleted = true) },
                save = { writes += it }, remoteDelete = { fail("Must not contact the server"); Result.success(Unit) })
            fail("Account changes must cancel the operation")
        } catch (_: CancellationException) { }
        assertTrue(writes.isEmpty())
    }

    @Test fun successfulDeleteRemainsDeletedAfterServerConfirmation() = runTest {
        assertTrue(delete { Result.success(Unit) }.isSuccess)
        assertEquals(original.copy(deleted = true), stored)
        assertEquals(1, writes.size)
    }

    @Test fun wrappedCancellationIsNotTreatedAsARejectedDelete() = runTest {
        val cancellation = CancellationException("screen closed")
        try {
            delete { Result.failure(cancellation) }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(1, writes.size)
    }

    @Test fun accountChangeDuringRollbackReadDoesNotWriteTheOldMessage() = runTest {
        var reads = 0
        try {
            deleteCollectionMessage(isCurrentSession = { sameSession },
                load = { if (++reads == 2) sameSession = false; stored },
                markDeleted = { it.copy(deleted = true) }, save = { stored = it; writes += it },
                remoteDelete = { Result.failure(IOException()) })
            fail("Account changes must cancel the rollback")
        } catch (_: CancellationException) { }
        assertEquals(1, writes.size)
    }
}
