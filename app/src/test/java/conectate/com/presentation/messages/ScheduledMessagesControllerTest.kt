package conectate.com.presentation.messages

import conectate.com.data.model.ScheduledMessageJob
import conectate.com.data.repository.message.ScheduledMessagesKey
import conectate.com.data.repository.message.ScheduledMessagesSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScheduledMessagesControllerTest {
    private val key = ScheduledMessagesKey("a", "dm", "chat")
    private fun snapshot(id: String = "cached") = ScheduledMessagesSnapshot(listOf(
        ScheduledMessageJob(id, "dm", "chat", "contenido", "text", scheduled_for = "2026-09-10T00:00:00Z", status = "pending")
    ), true)

    @Test fun `opening chat loads cache without a remote request`() = runTest {
        var requests = 0
        val controller = ScheduledMessagesController(this, { snapshot() }, { requests++; Result.success(snapshot("remote")) }, { true })
        controller.load(key, refreshRemote = false)
        advanceUntilIdle()
        assertEquals("cached", controller.state.value.jobs.single().id)
        assertEquals(0, requests)
        assertFalse(controller.state.value.isLoading)
    }

    @Test fun `explicit refresh exposes cache before network and retains it on failure`() = runTest {
        val response = CompletableDeferred<Result<ScheduledMessagesSnapshot>>()
        val controller = ScheduledMessagesController(this, { snapshot() }, { response.await() }, { true })
        controller.load(key, true)
        runCurrent()
        assertEquals("cached", controller.state.value.jobs.single().id)
        assertTrue(controller.state.value.isLoading)
        response.complete(Result.failure(Exception("Request timeout https://secret.example/rest")))
        advanceUntilIdle()
        assertFalse(controller.state.value.isLoading)
        assertEquals("cached", controller.state.value.jobs.single().id)
        assertEquals(ScheduledMessagesController.READ_ERROR, controller.state.value.error)
        assertFalse(controller.state.value.error!!.contains("https"))
    }

    @Test fun `empty result becomes confirmed empty only after a successful read`() = runTest {
        val controller = ScheduledMessagesController(this, { ScheduledMessagesSnapshot() },
            { Result.success(ScheduledMessagesSnapshot(hasRemoteSnapshot = true)) }, { true })
        controller.load(key, true)
        advanceUntilIdle()
        assertTrue(controller.state.value.hasRemoteSnapshot)
        assertTrue(controller.state.value.jobs.isEmpty())
        assertNull(controller.state.value.error)
    }

    @Test fun `unexpected failure cannot leave loading forever`() = runTest {
        val controller = ScheduledMessagesController(this, { snapshot() }, { error("failure") }, { true })
        controller.load(key, true)
        advanceUntilIdle()
        assertFalse(controller.state.value.isLoading)
        assertEquals("cached", controller.state.value.jobs.single().id)
        assertNotNull(controller.state.value.error)
    }

    @Test fun `closing dialog cancels request without error and keeps cache`() = runTest {
        var cancelled = false
        val controller = ScheduledMessagesController(this, { snapshot() }, {
            try { delay(25_000); Result.success(snapshot("late")) }
            finally { cancelled = true }
        }, { true })
        controller.load(key, true)
        runCurrent()
        controller.stop()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals("cached", controller.state.value.jobs.single().id)
        assertFalse(controller.state.value.isLoading)
        assertNull(controller.state.value.error)
    }

    @Test fun `retry after failure replaces error with fresh results`() = runTest {
        var fail = true
        val controller = ScheduledMessagesController(this, { snapshot() }, {
            if (fail) Result.failure(Exception("offline")) else Result.success(snapshot("fresh"))
        }, { true })
        controller.load(key, true)
        advanceUntilIdle()
        assertNotNull(controller.state.value.error)
        fail = false
        controller.load(key, true)
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        assertEquals("fresh", controller.state.value.jobs.single().id)
    }

    @Test fun `late response after account clear cannot restore previous account data`() = runTest {
        val response = CompletableDeferred<Unit>()
        var active = true
        val controller = ScheduledMessagesController(this, { snapshot() }, {
            withContext(NonCancellable) { response.await(); Result.success(snapshot("late-account-a")) }
        }, { active })
        controller.load(key, true)
        runCurrent()
        active = false
        controller.clear()
        response.complete(Unit)
        advanceUntilIdle()
        assertEquals(ScheduledMessagesUiState(), controller.state.value)
    }

    @Test fun `new conversation and newer request cannot receive older result`() = runTest {
        val oldReply = CompletableDeferred<Unit>()
        var attempt = 0
        val controller = ScheduledMessagesController(this, { ScheduledMessagesSnapshot() }, {
            if (++attempt == 1) withContext(NonCancellable) { oldReply.await(); Result.success(snapshot("old")) }
            else Result.success(snapshot("new"))
        }, { true })
        controller.load(key, true)
        runCurrent()
        controller.load(key.copy(targetId = "other"), true)
        runCurrent()
        assertEquals("new", controller.state.value.jobs.single().id)
        oldReply.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", controller.state.value.jobs.single().id)
    }

    @Test fun `external cancellation stops loading without displaying an error`() = runTest {
        val controller = ScheduledMessagesController(this, { snapshot() },
            { throw CancellationException("session closed") }, { true })
        controller.load(key, true)
        advanceUntilIdle()
        assertFalse(controller.state.value.isLoading)
        assertNull(controller.state.value.error)
    }

    @Test fun `legacy ownership warning is visible without exposing unowned content`() = runTest {
        val controller = ScheduledMessagesController(this, { ScheduledMessagesSnapshot(legacyJobsNeedOwner = true) },
            { error("cache only") }, { true })
        controller.load(key, false)
        advanceUntilIdle()
        assertTrue(controller.state.value.legacyJobsNeedOwner)
        assertTrue(controller.state.value.jobs.isEmpty())
    }

    @Test fun `repeated cancel is blocked through refresh and does not contaminate another account`() = runTest {
        val controller = ScheduledMessagesController(this, { snapshot() }, { Result.success(snapshot()) }, { true })
        controller.load(key, false)
        advanceUntilIdle()
        assertTrue(controller.beginCancellation(key, "cached"))
        assertFalse(controller.beginCancellation(key, "cached"))
        controller.load(key, true)
        advanceUntilIdle()
        assertEquals(setOf("cached"), controller.state.value.cancellingIds)
        controller.finishCancellation(key, "cached")
        assertTrue(controller.beginCancellation(key, "cached"))
        val other = key.copy(accountId = "b")
        controller.load(other, false)
        advanceUntilIdle()
        controller.finishCancellation(key, "cached")
        assertTrue(controller.state.value.cancellingIds.isEmpty())
        assertFalse(controller.beginCancellation(key, "cached"))
    }
}
