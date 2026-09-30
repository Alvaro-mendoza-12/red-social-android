package conectate.com.presentation.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCollaborationPollingTest {
    @Test fun `slow capabilities never delay guest invitations`() = runTest {
        val capabilities = CompletableDeferred<Unit>()
        val invitationTimes = mutableListOf<Long>()
        var roomReads = 0
        val job = backgroundScope.launch {
            pollLiveCollaboration(
                refreshInvitations = { invitationTimes += currentTime },
                refreshCapabilities = { capabilities.await() },
                refreshRoom = { roomReads++ },
                refreshBattle = {}, refreshSupporters = {}, refreshGifts = {}
            )
        }
        runCurrent()
        assertEquals(listOf(0L), invitationTimes)
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(listOf(0L, 4_000L), invitationTimes)
        assertEquals(0, roomReads)
        capabilities.complete(Unit)
        runCurrent()
        assertEquals(1, roomReads)
        job.cancelAndJoin()
        advanceTimeBy(8_000L)
        runCurrent()
        assertEquals(listOf(0L, 4_000L), invitationTimes)
    }

    @Test fun `independent optional reads run concurrently without overlapping cycles`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        suspend fun read(name: String) {
            started += name
            gate.await()
        }
        val job = backgroundScope.launch {
            pollLiveCollaboration(
                refreshInvitations = {}, refreshCapabilities = {},
                refreshRoom = { read("room") }, refreshBattle = { read("battle") },
                refreshSupporters = { read("supporters") }, refreshGifts = { read("gifts") }
            )
        }
        runCurrent()
        assertEquals(setOf("room", "battle", "supporters", "gifts"), started.toSet())
        assertEquals(4, started.size)
        advanceTimeBy(8_000L)
        runCurrent()
        assertEquals(4, started.size)
        job.cancelAndJoin()
    }

    @Test fun `cancellation stops polling even if an API converts it into failure`() = runTest {
        var reads = 0
        val job = backgroundScope.launch {
            pollLiveCollaboration(
                refreshInvitations = {
                    reads++
                    try { delay(60_000L) } catch (_: CancellationException) { }
                },
                refreshCapabilities = {}, refreshRoom = {}, refreshBattle = {},
                refreshSupporters = {}, refreshGifts = {}
            )
        }
        runCurrent()
        assertEquals(1, reads)
        job.cancelAndJoin()
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(1, reads)
    }
}
