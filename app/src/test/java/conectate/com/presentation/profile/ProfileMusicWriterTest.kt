package conectate.com.presentation.profile

import conectate.com.data.model.ProfileMusicSummary
import conectate.com.data.model.withReaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileMusicWriterTest {
    private val initial = ProfileMusicSummary(profileId = "owner", musicKey = "song", counts = mapOf("LOVE" to 2), total = 2)

    @Test fun optimisticSwitchAndRemovalNeverProduceNegativeCounts() {
        val liked = initial.withReaction("LOVE")
        assertEquals(3, liked.total)
        assertEquals(initial, liked.withReaction(null))
        val changed = liked.withReaction("FIRE")
        assertEquals(2, changed.counts["LOVE"])
        assertEquals(1, changed.counts["FIRE"])
        assertEquals(3, changed.total)
    }

    @Test fun rapidTapSerializesRequestsAndPersistsTheLastChoice() = runTest {
        var state = initial
        val first = CompletableDeferred<ProfileMusicSummary>()
        val calls = mutableListOf<String?>()
        val writer = ProfileMusicWriter(backgroundScope, { state }, { current, reaction ->
            calls += reaction
            Result.success(if (calls.size == 1) first.await() else current.withReaction(reaction))
        }, { state = it }, { fail() })
        writer.select("LOVE"); runCurrent()
        writer.select("FIRE")
        assertEquals("FIRE", state.reaction)
        first.complete(initial.withReaction("LOVE")); runCurrent()
        assertEquals(listOf("LOVE", "FIRE"), calls)
        assertEquals("FIRE", state.reaction)
        writer.select("FIRE"); runCurrent()
        assertNull(state.reaction)
        assertEquals(2, state.total)
    }

    @Test fun accountOrTrackChangeFencesAnOldCompletion() = runTest {
        var state = initial
        val response = CompletableDeferred<Result<ProfileMusicSummary>>()
        var failures = 0
        val writer = ProfileMusicWriter(backgroundScope, { state }, { _, _ -> response.await() }, { state = it }, { failures++ })
        writer.select("LOVE"); runCurrent()
        writer.clear(); state = initial.copy(musicKey = "new-song")
        response.complete(Result.failure(Exception("offline"))); runCurrent()
        assertEquals("new-song", state.musicKey)
        assertNull(state.reaction)
        assertEquals(0, failures)
    }

    @Test fun aFailedWriteRestoresConfirmedCountsAndAllowsRetry() = runTest {
        var state = initial
        var failures = 0
        val writer = ProfileMusicWriter(backgroundScope, { state }, { _, _ -> Result.failure(Exception("offline")) },
            { state = it }, { failures++ })
        writer.select("LOVE"); runCurrent()
        assertEquals(initial, state)
        assertFalse(writer.pending)
        assertEquals(1, failures)
        writer.select("INVALID"); runCurrent()
        assertEquals(1, failures)
    }
}
