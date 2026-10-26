package conectate.com.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LiveSocketStartupTest {
    @Test fun unrelatedRoomDoesNotCompleteAdmission() = runTest {
        val startup = LiveSocketStartup("room-a")
        assertFalse(startup.acceptRoom("room-b"))
        assertFalse(startup.acceptRoom(""))
        assertTrue(startup.isPending)
        assertTrue(startup.acceptRoom(" ROOM-A "))
        startup.await()
        assertFalse(startup.isPending)
    }

    @Test fun duplicateAcknowledgementCannotReplaceAnAcceptedRoom() = runTest {
        val startup = LiveSocketStartup("room-a")
        assertTrue(startup.acceptRoom("room-a"))
        assertFalse(startup.reject(IllegalStateException("late event")))
        assertFalse(startup.acceptRoom("room-a"))
        startup.cancel()
        startup.await()
    }

    @Test fun explicitRejectionIsReturnedInsteadOfSuccessfulJoin() = runTest {
        val startup = LiveSocketStartup("room-a")
        val error = IllegalStateException("Directo finalizado")
        assertTrue(startup.reject(error))
        val result = runCatching { awaitLiveConnection<Unit> { startup.await(); Result.success(Unit) } }
        // This is the same throwing boundary consumed by joinLiveAsViewer.
        assertEquals(error.message, result.exceptionOrNull()?.message)
        assertFalse(startup.isPending)
    }

    @Test fun rejectedRoomCannotBeRevivedByALateCount() = runTest {
        val startup = LiveSocketStartup("room-a")
        startup.reject(IllegalStateException("removed"))
        assertFalse(startup.acceptRoom("room-a"))
        assertEquals("removed", runCatching { startup.await() }.exceptionOrNull()?.message)
    }

    @Test fun disconnectCancelsAWaitingAdmission() = runTest {
        val startup = LiveSocketStartup("room-a")
        startup.cancel()
        assertFalse(startup.acceptRoom("room-a"))
        assertTrue(runCatching { startup.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun serverThatNeverAcknowledgesEndsAtTheConnectionDeadline() = runTest {
        val startup = LiveSocketStartup("room-a")
        val result = awaitLiveConnection<Unit>(100) { startup.await(); Result.success(Unit) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("tardó demasiado"))
        startup.cancel()
    }
}
