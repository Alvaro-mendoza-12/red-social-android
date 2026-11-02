package conectate.com.presentation.profile

import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRequestsTest {
    private class Owner {
        var id: String? = "a"
        var generation = 0
        fun capture(): AuthSessionLease {
            val expected = generation
            val account = id
            return AuthSessionLease(account) { account == id && expected == generation }
        }
        fun change(id: String?) { generation++; this.id = id }
    }

    @Test fun `an old response cannot replace a newer load or finish its progress indicator`() = runTest {
        val owner = Owner()
        val requests = NotificationRequests(backgroundScope, owner::capture)
        val old = CompletableDeferred<Result<String>>()
        val fresh = CompletableDeferred<Result<String>>()
        var value = ""
        var busy = false
        requests.run("list", started = { busy = true },
            action = { withContext(NonCancellable) { old.await() } },
            success = { value = it }, failure = { fail("Old failure") }, finished = { busy = false })
        runCurrent()
        requests.run("list", started = { busy = true }, action = { fresh.await() },
            success = { value = it }, failure = { fail("Fresh failure") }, finished = { busy = false })
        runCurrent()
        old.complete(Result.success("obsolete"))
        runCurrent()
        assertEquals("", value)
        assertTrue(busy)
        fresh.complete(Result.success("current"))
        runCurrent()
        assertEquals("current", value)
        assertFalse(busy)
    }

    @Test fun `returning to the same account does not revive the earlier session result`() = runTest {
        val owner = Owner()
        val requests = NotificationRequests(backgroundScope, owner::capture)
        val response = CompletableDeferred<Result<String>>()
        var applied = false
        requests.run("list", action = { response.await() }, success = { applied = true }, failure = { applied = true })
        runCurrent()
        owner.change("b"); owner.change("a")
        response.complete(Result.success("private old result"))
        runCurrent()
        assertFalse(applied)
    }

    @Test fun `mutation taps reserve immediately and can retry after a failure`() = runTest {
        val owner = Owner()
        val requests = NotificationRequests(backgroundScope, owner::capture)
        val response = CompletableDeferred<Result<Unit>>()
        var starts = 0
        var failures = 0
        repeat(4) {
            requests.run("save", replace = false, started = { starts++ }, action = { response.await() },
                success = {}, failure = { failures++ })
        }
        assertEquals(1, starts)
        runCurrent()
        response.complete(Result.failure(IOException("offline")))
        runCurrent()
        assertEquals(1, failures)
        requests.run("save", replace = false, started = { starts++ }, action = { Result.success(Unit) },
            success = {}, failure = { fail("Retry failed") })
        runCurrent()
        assertEquals(2, starts)
    }

    @Test fun `timeout explains failure and releases the current request`() = runTest {
        val owner = Owner()
        val requests = NotificationRequests(backgroundScope, owner::capture, timeoutMs = 100)
        var failures = 0
        var finished = 0
        requests.run("list", action = { delay(1_000); Result.success(Unit) }, success = { fail("Timed out") },
            failure = { failures++ }, finished = { finished++ })
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(1, failures)
        assertEquals(1, finished)
    }

    @Test fun `explicit replacement cancellation stays silent and cleans progress once`() = runTest {
        val owner = Owner()
        val requests = NotificationRequests(backgroundScope, owner::capture)
        var finishes = 0
        var failures = 0
        var cancelled = 0
        requests.run("list", action = { awaitCancellation() }, success = { _: Unit -> },
            failure = { failures++ }, cancelled = { cancelled++ }, finished = { finishes++ })
        runCurrent()
        requests.cancelAll()
        runCurrent()
        assertEquals(1, finishes)
        assertEquals(0, failures)
        assertEquals(0, cancelled)
    }

    @Test fun `an interrupted current load can offer retry without swallowing cancellation`() = runTest {
        val owner = Owner()
        val requests = NotificationRequests(backgroundScope, owner::capture)
        var cancelled = 0
        var failures = 0
        var finishes = 0
        requests.run("list", action = { Result.failure<Unit>(CancellationException("request interrupted")) },
            success = {}, failure = { failures++ }, cancelled = { cancelled++ }, finished = { finishes++ })
        runCurrent()
        assertEquals(1, cancelled)
        assertEquals(0, failures)
        assertEquals(1, finishes)
    }

    @Test fun `missing account never starts a request`() = runTest {
        val owner = Owner().apply { change(null) }
        val requests = NotificationRequests(backgroundScope, owner::capture)
        var calls = 0
        var failures = 0
        requests.run("list", action = { calls++; Result.success(Unit) }, success = {}, failure = { failures++ })
        runCurrent()
        assertEquals(0, calls)
        assertEquals(1, failures)
    }

    @Test fun `unexpected transport exception releases progress and allows another load`() = runTest {
        val owner = Owner()
        val requests = NotificationRequests(backgroundScope, owner::capture)
        var busy = false
        var errors = 0
        requests.run<Unit>("list", started = { busy = true }, action = { throw IOException("offline") },
            success = {}, failure = { errors++ }, finished = { busy = false })
        runCurrent()
        assertFalse(busy)
        assertEquals(1, errors)
        requests.run("list", action = { Result.success(Unit) }, success = { errors = 0 }, failure = {})
        runCurrent()
        assertEquals(0, errors)
    }
}
