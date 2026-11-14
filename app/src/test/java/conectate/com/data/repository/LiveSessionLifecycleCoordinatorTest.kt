package conectate.com.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSessionLifecycleCoordinatorTest {
    @Test fun `a late no-op for a previous visit cannot consume the current visits close`() = runTest {
        val coordinator = LiveSessionCloseCoordinator()
        var active = "new-visit"
        coordinator.closeOnce("old-visit") {
            if (active == "old-visit") active = ""
            Result.success(Unit)
        }
        coordinator.closeOnce("new-visit") {
            active = ""
            Result.success(Unit)
        }
        assertEquals("", active)
    }

    @Test fun `different visits serialize teardown and duplicate requests share only their own result`() = runTest {
        val coordinator = LiveSessionCloseCoordinator()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val old = async { coordinator.closeOnce("old") { calls += "old"; release.await(); Result.failure(IllegalStateException("old result")) } }
        val current = async { coordinator.closeOnce("new") { calls += "new"; Result.success(Unit) } }
        val duplicate = async { coordinator.closeOnce("new") { calls += "duplicate"; Result.failure(IllegalStateException()) } }
        testScheduler.runCurrent()
        assertEquals(listOf("old"), calls)
        assertFalse(coordinator.resetForNewSession())
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        assertTrue(current.await().isSuccess)
        assertTrue(duplicate.await().isSuccess)
        assertEquals(listOf("old", "new"), calls)
    }

    @Test
    fun `concurrent close requests share one suspendable teardown`() = runTest {
        val coordinator = LiveSessionCloseCoordinator()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)

        val first = async {
            coordinator.closeOnce {
                calls.incrementAndGet()
                release.await()
                Result.success(Unit)
            }
        }
        val second = async {
            coordinator.closeOnce {
                calls.incrementAndGet()
                Result.success(Unit)
            }
        }

        testScheduler.runCurrent()
        assertEquals(1, calls.get())
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        release.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(1, calls.get())
        assertTrue(coordinator.resetForNewSession())
    }

    @Test
    fun `media transition gate rejects overlap and can be reused`() {
        val gate = LiveMediaTransitionGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        assertTrue(gate.isTransitioning())

        gate.finish()
        assertFalse(gate.isTransitioning())
        assertTrue(gate.tryStart())
    }

    @Test
    fun `projection stop during startup is consumed by atomic commit`() {
        val coordinator = LiveProjectionStateCoordinator()

        coordinator.beginPending(token = "new-token", suppressToken = "old-token")
        assertEquals(
            ProjectionStopDisposition.PENDING,
            coordinator.markStopped("new-token")
        )
        assertEquals(
            PendingProjectionCommit.STOPPED,
            coordinator.commitPending("new-token")
        )
        assertFalse(coordinator.isActive("new-token"))
    }

    @Test
    fun `projection callback after commit observes active token`() {
        val coordinator = LiveProjectionStateCoordinator()

        coordinator.beginPending(token = "new-token", suppressToken = null)
        assertEquals(
            PendingProjectionCommit.ACTIVATED,
            coordinator.commitPending("new-token")
        )
        assertEquals(
            ProjectionStopDisposition.ACTIVE,
            coordinator.markStopped("new-token")
        )
        assertTrue(coordinator.isActive("new-token"))
    }
}
