package conectate.com.data.repository

import org.junit.Assert.*
import org.junit.Test

class LiveGuestMediaStartCoordinatorTest {
    @Test fun leavingInvalidatesAPendingStart() {
        val coordinator = LiveGuestMediaStartCoordinator()
        val pending = coordinator.begin("live")
        coordinator.invalidate()
        var committed = false
        assertFalse(coordinator.completeIfCurrent(pending) { committed = true })
        assertFalse(committed)
        assertFalse(coordinator.isStarting("live"))
    }

    @Test fun reenteringTheSameLiveNeverAcceptsAnOldCallback() {
        val coordinator = LiveGuestMediaStartCoordinator()
        val old = coordinator.begin("live")
        coordinator.invalidate()
        val current = coordinator.begin("live")
        assertFalse(coordinator.completeIfCurrent(old) { fail("Stale callback") })
        assertTrue(coordinator.isStarting("live"))
        assertTrue(coordinator.completeIfCurrent(current) { })
    }

    @Test fun anotherStartSupersedesTheFirst() {
        val coordinator = LiveGuestMediaStartCoordinator()
        val first = coordinator.begin("first")
        val second = coordinator.begin("second")
        assertFalse(coordinator.completeIfCurrent(first) { fail("Old callback") })
        assertTrue(coordinator.completeIfCurrent(second) { })
        assertFalse(coordinator.completeIfCurrent(second) { fail("Duplicate callback") })
    }

    @Test fun completionUsesTheLatestCameraChoiceUnderTheSameLock() {
        val coordinator = LiveGuestMediaStartCoordinator()
        val attempt = coordinator.begin("live")
        var requestedVideo = true
        var publishedVideo = true
        coordinator.withLock { requestedVideo = false }
        assertTrue(coordinator.completeIfCurrent(attempt) { publishedVideo = requestedVideo })
        assertFalse(publishedVideo)
        assertFalse(coordinator.isStarting("live"))
    }

    @Test fun anUnrelatedSessionOrReplacedManagerCannotCommit() {
        val coordinator = LiveGuestMediaStartCoordinator()
        val attempt = coordinator.begin("live")
        assertFalse(coordinator.completeIfCurrent(attempt, eligible = { false }) { fail("Unavailable session") })
        assertFalse(coordinator.isStarting("live"))
    }

    @Test fun anUnchangedGuestSnapshotDoesNotRestartIce() {
        assertFalse(shouldConnectLiveGuestPeer("guest", "self", setOf("guest"), false))
    }

    @Test fun aNewGuestOrReplacedRemoteSocketConnects() {
        assertTrue(shouldConnectLiveGuestPeer("guest", "self", emptySet(), false))
        assertTrue(shouldConnectLiveGuestPeer("new-guest-socket", "self", setOf("old-guest-socket"), false))
    }

    @Test fun reconnectingTheLocalSocketRecoversUnchangedGuests() {
        assertTrue(shouldConnectLiveGuestPeer("guest", "new-self", setOf("guest"), true))
    }

    @Test fun selfAndBlankSocketsNeverConnect() {
        assertFalse(shouldConnectLiveGuestPeer("self", "self", emptySet(), true))
        assertFalse(shouldConnectLiveGuestPeer("", "self", emptySet(), true))
    }
}
