package conectate.com.data.repository.message

import conectate.com.data.local.entity.MessageEntity
import conectate.com.domain.sync.PendingSyncRetryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PendingMessageBatchRunnerTest {
    private fun message(id: String, owner: String = "active") = MessageEntity(
        id = id, chat_id = "chat", sender_id = owner, content = id, content_type = "text",
        created_at = "2026-09-04T00:00:00Z", status = "sending", is_synced = false, pending_action = "send"
    )

    private class Fixture(initial: List<MessageEntity>) {
        val queue = initial.toMutableList()
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var tokenCalls = 0
        var current = true
        var token: suspend () -> Result<String> = { Result.success("token") }
        var deliver: suspend (MessageEntity) -> PendingSendOutcome = {
            queue.remove(it)
            PendingSendOutcome.SENT
        }
        val runner = PendingMessageBatchRunner(
            loadPending = { queue.toList() },
            accessToken = { tokenCalls++; token() },
            send = { item, _ -> sent += item.id; deliver(item) },
            markTerminalFailure = { failed += it.id; queue.remove(it) }
        )
        suspend fun run(limit: Int = 50) = runner.run(" ACTIVE ", limit) { current }
    }

    @Test fun `inactive account rows do not consume the active batch`() = runTest {
        val f = Fixture((1..60).map { message("old-$it", "other") } + message("mine", " Active "))
        assertEquals(1, f.run(1).getOrThrow())
        assertEquals(listOf("mine"), f.sent)
        assertEquals(60, f.queue.size)
    }

    @Test fun `empty active outbox does not request a token`() = runTest {
        val f = Fixture(listOf(message("old", "other")))
        assertEquals(0, f.run().getOrThrow())
        assertEquals(0, f.tokenCalls)
    }

    @Test fun `token failure retains messages and durable retry`() = runTest {
        val f = Fixture(listOf(message("one")))
        f.token = { Result.failure(java.io.IOException("offline")) }
        assertTrue(f.run().exceptionOrNull() is PendingSyncRetryException)
        assertEquals(1, f.queue.size)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun `temporary delivery failure remains pending`() = runTest {
        val f = Fixture(listOf(message("one")))
        f.deliver = { PendingSendOutcome.RETRY_LATER }
        assertTrue(f.run().exceptionOrNull() is PendingSyncRetryException)
        assertEquals(1, f.queue.size)
        assertTrue(f.failed.isEmpty())
    }

    @Test fun `batch remainder requests continuation until drained`() = runTest {
        val f = Fixture(listOf(message("one"), message("two")))
        assertTrue(f.run(1).exceptionOrNull() is PendingSyncRetryException)
        assertEquals(listOf("two"), f.queue.map { it.id })
        assertEquals(1, f.run(1).getOrThrow())
        assertEquals(listOf("one", "two"), f.sent)
    }

    @Test fun `terminal rejection does not stay in automatic queue`() = runTest {
        val f = Fixture(listOf(message("one")))
        f.deliver = { PendingSendOutcome.TERMINAL_FAILURE }
        assertEquals(0, f.run().getOrThrow())
        assertEquals(listOf("one"), f.failed)
        assertTrue(f.queue.isEmpty())
    }

    @Test fun `switch during token refresh never sends with the new session`() = runTest {
        val f = Fixture(listOf(message("one")))
        f.token = { f.current = false; Result.success("new-account-token") }
        assertTrue(f.run().exceptionOrNull() is PendingSyncRetryException)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun `switch during delivery stops the batch and prevents terminal writes`() = runTest {
        val f = Fixture(listOf(message("one"), message("two")))
        f.deliver = { f.current = false; PendingSendOutcome.TERMINAL_FAILURE }
        assertTrue(f.run().exceptionOrNull() is PendingSyncRetryException)
        assertEquals(listOf("one"), f.sent)
        assertTrue(f.failed.isEmpty())
        assertEquals(2, f.queue.size)
    }

    @Test fun `realtime acknowledgement during failed send is not reverted`() = runTest {
        val f = Fixture(listOf(message("one")))
        f.deliver = { f.queue.remove(it); PendingSendOutcome.RETRY_LATER }
        assertEquals(0, f.run().getOrThrow())
        assertTrue(f.queue.isEmpty())
    }

    @Test fun `cancellation propagates without turning into a retry result`() = runTest {
        val f = Fixture(listOf(message("one")))
        f.token = { Result.failure(CancellationException("cancelled")) }
        try {
            f.run()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, f.queue.size)
        }
    }
}
