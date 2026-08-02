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
