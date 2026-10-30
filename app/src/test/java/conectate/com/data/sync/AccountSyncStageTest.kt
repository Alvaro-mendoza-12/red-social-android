package conectate.com.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountSyncStageTest {
    @Test fun `a cancelled result stops the cycle before the next stage`() = runTest {
        val stage = AccountSyncStage("a") { "a" }
        val cancellation = CancellationException("repository stopped")
        val executed = mutableListOf<String>()
        try {
            stage.run<Int> { executed += "scheduled"; Result.failure(cancellation) }
            stage.run { executed += "groups"; Result.success(Unit) }
            fail("must stop")
        } catch (caught: CancellationException) { assertSame(cancellation, caught) }
        assertEquals(listOf("scheduled"), executed)
    }

    @Test fun `account changed before start does not execute the operation`() = runTest {
        val stage = AccountSyncStage("a") { "b" }
        var called = false
        try { stage.run { called = true; Result.success(1) }; fail("must stop") }
        catch (_: CancellationException) { }
        assertFalse(called)
    }

    @Test fun `account changed during a stage cannot continue or report its success`() = runTest {
        var account: String? = "a"
        val stage = AccountSyncStage("a") { account }
        var accepted = false
        try {
            stage.run { account = "b"; Result.success(4) }
            accepted = true
            fail("must stop")
        } catch (_: CancellationException) { }
        assertFalse(accepted)
    }

    @Test fun `logout while awaiting a failed response stops instead of counting a failure`() = runTest {
        var account: String? = "a"
        val stage = AccountSyncStage("a") { account }
        try {
            stage.run<Int> { account = null; Result.failure(IllegalStateException("network")) }
            fail("must stop")
        } catch (_: CancellationException) { }
    }

    @Test fun `ordinary failure is preserved for the caller retry policy`() = runTest {
        val failure = IllegalStateException("network")
        assertSame(failure, AccountSyncStage("a") { "a" }.run<Int> { Result.failure(failure) }.exceptionOrNull())
    }

    @Test fun `normalized account can complete all stages`() = runTest {
        val stage = AccountSyncStage(" A ") { "a" }
        repeat(7) { assertEquals(it, stage.run { Result.success(it) }.getOrThrow()) }
    }

    @Test fun `cancellation thrown by the repository propagates unchanged`() = runTest {
        val failure = CancellationException("closed")
        try { AccountSyncStage("a") { "a" }.run<Int> { throw failure }; fail("must stop") }
        catch (caught: CancellationException) { assertSame(failure, caught) }
    }

    @Test fun `real coroutine cancellation is detected even if repository returns success`() = runTest {
        var nextStage = false
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            val stage = AccountSyncStage("a") { "a" }
            stage.run { currentCoroutineContext().cancel(); Result.success(1) }
            nextStage = true
        }
        try { pending.await(); fail("must stop") } catch (_: CancellationException) { }
        assertFalse(nextStage)
    }
}
