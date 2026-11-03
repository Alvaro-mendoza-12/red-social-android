package conectate.com.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.model.MultiAccountPendingDestination
import conectate.com.test.UnitTestApplication
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class InboxStateStoreTest {
    private fun preferences(): SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("inbox-state-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun destination(kind: String = "chat", id: String = "one", at: Long = 100L) =
        MultiAccountPendingDestination(kind, id, preview = "Preview $id", focusMessageId = "message-$id", receivedAt = at)
    private fun remote(vararg destinations: MultiAccountPendingDestination) = InactiveAccountUnreadSnapshot(
        destinations.size, destinations.map { it.conversationKey }.toSet(), destinations.maxByOrNull { it.receivedAt },
    )
    private fun complete(vararg direct: MultiAccountPendingDestination) = mapOf(
        InboxUnreadSection.DIRECT to remote(*direct),
        InboxUnreadSection.GROUP to remote(),
        InboxUnreadSection.COMMUNITY to remote(),
    )

    @Test fun readsTheExistingPreferenceContractWithoutClearingIt() {
        val prefs = preferences()
        val latest = destination("group", "one")
        prefs.edit().putInt("cached_direct_unread_a", 4)
            .putStringSet("pending_conversation_a", setOf(" GROUP:ONE "))
            .putString("latest_destination_a", Json.encodeToString(latest)).commit()
        val state = InboxStateStore { prefs }.snapshot("a")
        assertEquals(4, state.cachedUnreadConversations)
        assertEquals(setOf("group:one"), state.pendingConversationKeys)
        assertEquals(latest, state.latestPendingDestination)
    }

    @Test fun aPartialReadPreservesTheFailedSectionAndItsLatestDestination() {
        val state = InboxStateStore { preferences() }
        state.registerHint("a", destination("chat", "old", 50))
        val group = destination("group", "unavailable", 300)
        state.registerHint("a", group)
        val before = state.snapshot("a")
        assertTrue(state.applyRefresh(before, mapOf(
            InboxUnreadSection.DIRECT to remote(destination("chat", "new", 200)),
            InboxUnreadSection.COMMUNITY to remote(),
        )))
        val after = state.snapshot("a")
        assertEquals(setOf("chat:new", "group:unavailable"), after.pendingConversationKeys)
        assertEquals(2, after.cachedUnreadConversations)
        assertEquals(group, after.latestPendingDestination)
    }

    @Test fun onlyACompleteReadCanReplaceAnUndividedHistoricalCounterWithZero() {
        val state = InboxStateStore { preferences() }
        state.persistActive("a", 7)
        assertTrue(state.applyRefresh(state.snapshot("a"), mapOf(InboxUnreadSection.DIRECT to remote())))
        assertEquals(7, state.snapshot("a").cachedUnreadConversations)
        assertTrue(state.applyRefresh(state.snapshot("a"), complete()))
        val empty = state.snapshot("a")
        assertEquals(0, empty.cachedUnreadConversations)
        assertTrue(empty.pendingConversationKeys.isEmpty())
        assertNull(empty.latestPendingDestination)
    }

    @Test fun aTotalFailureLeavesAllPreviousStateUntouched() {
        val state = InboxStateStore { preferences() }
        state.persistActive("a", 3)
        state.registerHint("a", destination("group"))
        val before = state.snapshot("a")
        assertFalse(state.applyRefresh(before, emptyMap()))
        assertEquals(before, state.snapshot("a"))
    }

    @Test fun aNewHintCannotBeErasedByAnOlderEmptyResponse() {
        val state = InboxStateStore { preferences() }
        val before = state.snapshot("a")
        state.registerHint("a", destination())
        assertFalse(state.applyRefresh(before, complete()))
        assertEquals(setOf("chat:one"), state.snapshot("a").pendingConversationKeys)
    }

    @Test fun anAcknowledgementPreventsAnOlderResponseFromRecreatingItsHints() {
        val state = InboxStateStore { preferences() }
        state.registerHint("a", destination())
        val before = state.snapshot("a")
        state.acknowledge("a")
        assertFalse(state.applyRefresh(before, complete(destination())))
        assertTrue(state.snapshot("a").pendingConversationKeys.isEmpty())
        assertNull(state.snapshot("a").latestPendingDestination)
    }

    @Test fun evenAnEmptyAcknowledgementInvalidatesTheOutstandingRead() {
        val state = InboxStateStore { preferences() }
        val before = state.snapshot("a")
        state.acknowledge("a")
        assertFalse(state.applyRefresh(before, complete(destination())))
    }

    @Test fun anActiveSnapshotCannotBeReplacedByAnEarlierInactiveRead() {
        val state = InboxStateStore { preferences() }
        val before = state.snapshot("a")
        state.persistActive("a", 4)
        assertFalse(state.applyRefresh(before, complete()))
        assertEquals(4, state.snapshot("a").cachedUnreadConversations)
    }

    @Test fun pruningKeepsOtherAccountsAndUnknownSettingsAndInvalidatesOldReads() {
        val prefs = preferences()
        val state = InboxStateStore { prefs }
        state.registerHint("a", destination())
        state.registerHint("b", destination("community", "kept"))
        prefs.edit().putString("future_setting", "keep").commit()
        val before = state.snapshot("a")
        state.prune(setOf("b"))
        assertFalse(state.applyRefresh(before, complete(destination())))
        assertTrue(state.snapshot("a").pendingConversationKeys.isEmpty())
        assertEquals(setOf("community:kept"), state.snapshot("b").pendingConversationKeys)
        assertEquals("keep", prefs.getString("future_setting", null))
    }

    @Test fun unrelatedAccountChangesDoNotCancelAValidRefresh() {
        val state = InboxStateStore { preferences() }
        val a = state.snapshot("a")
        state.registerHint("b", destination("group", "other"))
        assertTrue(state.applyRefresh(a, complete(destination())))
        assertEquals(setOf("chat:one"), state.snapshot("a").pendingConversationKeys)
        assertEquals(setOf("group:other"), state.snapshot("b").pendingConversationKeys)
    }

    @Test fun theLatestRequestedRefreshWinsEvenWhenTheEarlierResponseFinishesFirst() {
        val state = InboxStateStore { preferences() }
        val earlier = state.beginRefresh("a")
        val latest = state.beginRefresh("a")
        assertFalse(state.applyRefresh(earlier, complete(destination(id = "obsolete"))))
        assertTrue(state.applyRefresh(latest, complete(destination(id = "current"))))
        assertEquals(setOf("chat:current"), state.snapshot("a").pendingConversationKeys)
    }

    @Test fun aDelayedHintAddsItsConversationWithoutReplacingTheNewerPreview() {
        val state = InboxStateStore { preferences() }
        val latest = destination("community", "new", 500)
        state.registerHint("a", latest)
        state.registerHint("a", destination("chat", "old", 100))
        assertEquals(latest, state.snapshot("a").latestPendingDestination)
        assertEquals(setOf("community:new", "chat:old"), state.snapshot("a").pendingConversationKeys)
    }

    @Test fun anOlderResponseCannotRestoreThePreviewOfADeletedMessage() {
        val state = InboxStateStore { preferences() }
        state.registerHint("a", destination())
        val before = state.snapshot("a")
        state.redactLatest("a", "chat", "one", "message-one")
        assertFalse(state.applyRefresh(before, complete(destination())))
        assertEquals("Mensaje eliminado", state.snapshot("a").latestPendingDestination?.preview)
    }

    @Test fun invalidCrossSectionKeysCannotEnterAConfirmedScope() {
        val state = InboxStateStore { preferences() }
        val malformed = InactiveAccountUnreadSnapshot(50, setOf("group:wrong", "chat:", "chat:valid"), destination("group", "wrong"))
        assertTrue(state.applyRefresh(state.snapshot("a"), mapOf(InboxUnreadSection.DIRECT to malformed)))
        assertEquals(setOf("chat:valid"), state.snapshot("a").pendingConversationKeys)
        assertNull(state.snapshot("a").latestPendingDestination)
    }

    @Test fun deletionAlsoFencesAResponseWhenThatMessageWasNotTheCurrentPreview() {
        listOf(null, destination("group", "another", 500)).forEach { previous ->
            val state = InboxStateStore { preferences() }
            if (previous != null) state.registerHint("a", previous)
            val before = state.snapshot("a")
            state.redactLatest("a", "chat", "one", "message-one")
            assertFalse(state.applyRefresh(before, complete(destination())))
            assertEquals(previous, state.snapshot("a").latestPendingDestination)
        }
    }

    @Test fun concurrentDeliveriesKeepEveryDistinctConversation() {
        val state = InboxStateStore { preferences() }
        state.snapshot("a")
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..40).map { id -> workers.submit {
                check(start.await(5, TimeUnit.SECONDS))
                state.registerHint("a", destination(id = id.toString(), at = id.toLong()))
            } }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals((1..40).map { "chat:$it" }.toSet(), state.snapshot("a").pendingConversationKeys)
            assertEquals("40", state.snapshot("a").latestPendingDestination?.conversationId)
        } finally {
            start.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun preferenceObserversSeeOneCoherentRefreshAndTheListenerCanReadWithoutDeadlock() {
        val prefs = preferences()
        val state = InboxStateStore { prefs }
        val observed = mutableListOf<InboxStateSnapshot>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> observed += state.snapshot("a") }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            assertTrue(state.applyRefresh(state.snapshot("a"), complete(destination(id = "one"), destination(id = "two", at = 200))))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(observed.isNotEmpty())
            observed.forEach {
                assertEquals(2, it.cachedUnreadConversations)
                assertEquals(setOf("chat:one", "chat:two"), it.pendingConversationKeys)
                assertEquals("two", it.latestPendingDestination?.conversationId)
            }
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Test fun cancellationStopsBeforeAnotherSectionCanBeRead() {
        val cancellation = CancellationException("Stopped inbox refresh")
        var anotherRead = false
        val thrown = runCatching {
            inboxReadResult<String> { throw cancellation }
            anotherRead = true
        }.exceptionOrNull()
        assertSame(cancellation, thrown)
        assertFalse(anotherRead)
        val failure = IllegalStateException("Temporary connection failure")
        assertSame(failure, inboxReadResult<String> { throw failure }.exceptionOrNull())
    }
}
