package conectate.com.presentation.live

import androidx.lifecycle.ViewModelStore
import conectate.com.data.model.VirtualGift
import conectate.com.data.repository.LiveChannelPointsRepository
import conectate.com.data.repository.LiveRepositorySessionOwnershipTest
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.WalletRepository
import conectate.com.test.UnitTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ViewModel plus real repository; only HTTP/native/auth boundaries are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LiveScreenOwnershipTest {
    private fun createScreen(fixture: LiveRepositorySessionOwnershipTest.Fixture): LiveSessionViewModel {
        val wallet = mockk<WalletRepository>()
        every { wallet.coinBalance } returns MutableStateFlow(0)
        every { wallet.availableGifts } returns MutableStateFlow(emptyList<VirtualGift>())
        coEvery { wallet.fetchBalance() } returns Result.success(Unit)
        coEvery { wallet.fetchAvailableGifts() } returns Result.success(Unit)
        return LiveSessionViewModel(fixture.repository,
            mockk<LiveChannelPointsRepository>(relaxUnitFun = true), fixture.auth,
            mockk<UserRepository>(relaxUnitFun = true), wallet, fixture.dispatchers)
    }

    private fun exercise(
        viewer: Boolean = false,
        source: String = "audio",
        block: suspend TestScope.(LiveRepositorySessionOwnershipTest.Fixture, LiveSessionViewModel) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = LiveRepositorySessionOwnershipTest.Fixture(this, viewer, source)
        val viewModel = createScreen(fixture)
        val store = ViewModelStore().also { it.put("screen-under-test", viewModel) }
        try { block(fixture, viewModel) }
        finally { store.clear(); fixture.cancelBackgroundWork(); runCurrent(); Dispatchers.resetMain() }
    }

    private fun TestScope.startScreen(fixture: LiveRepositorySessionOwnershipTest.Fixture, vm: LiveSessionViewModel, viewer: Boolean = false) {
        if (viewer) vm.joinAsViewer(fixture.stream.id) else vm.host(fixture.stream.id)
        runCurrent()
        assertNotNull("The real screen must start before testing a retained action", fixture.repository.activeSession.value)
    }

    private suspend fun TestScope.replaceAccountVisit(fixture: LiveRepositorySessionOwnershipTest.Fixture, viewer: Boolean = false): String {
        val originalAccount = fixture.account
        fixture.switchTo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "fixture-b-visit")
        fixture.switchTo(originalAccount, "fixture-a-next-screen")
        runCurrent()
        val started = if (viewer) fixture.repository.joinLiveAsViewer(fixture.stream.id) else fixture.repository.hostLive(fixture.stream.id)
        assertTrue("The incoming visit must start before invoking the old screen: ${started.exceptionOrNull()}", started.isSuccess)
        return requireNotNull(fixture.repository.activeSession.value).sessionId
    }

    @Test fun anAwaitedCloseRetainedByTheOldHostCannotEndTheNextVisit() = exercise { fixture, oldScreen ->
        startScreen(fixture, oldScreen)
        val oldClose = oldScreen::endLiveAndAwait
        val next = replaceAccountVisit(fixture)
        oldClose()
        assertEquals("A host callback retained across a frame/account change cannot close the next visit", next,
            fixture.repository.activeSession.value?.sessionId)
    }

    @Test fun anAwaitedLeaveRetainedByTheOldViewerCannotEndTheNextVisit() = exercise(viewer = true) { fixture, oldScreen ->
        startScreen(fixture, oldScreen, viewer = true)
        val oldLeave = oldScreen::leaveLiveAndAwait
        val next = replaceAccountVisit(fixture, viewer = true)
        oldLeave()
        assertEquals("An old viewer's awaited leave must keep its own receipt", next, fixture.repository.activeSession.value?.sessionId)
    }

    @Test fun disposingAnOldViewerCannotEnqueueAClosureForTheNewVisit() = exercise(viewer = true) { fixture, oldScreen ->
        startScreen(fixture, oldScreen, viewer = true)
        val onDispose = oldScreen::leaveLive
        val next = replaceAccountVisit(fixture, viewer = true)
        onDispose(); runCurrent()
        assertEquals("The old route's onDispose must not capture the newly active session", next, fixture.repository.activeSession.value?.sessionId)
    }

    @Test fun aHostRequestQueuedByTheOldScreenCannotAdoptAReturningAccount() = exercise { fixture, oldScreen ->
        val originalAccount = fixture.account
        oldScreen.host(fixture.stream.id)
        fixture.switchTo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "fixture-b-visit")
        fixture.switchTo(originalAccount, "fixture-a-next-screen")
        runCurrent()
        assertNull("A queued request must retain the screen's original account visit", fixture.repository.activeSession.value)
    }

    @Test fun theCurrentViewerCanStillLeaveItsOwnSession() = exercise(viewer = true) { fixture, screen ->
        startScreen(fixture, screen, viewer = true)
        val result = screen.leaveLiveAndAwait()
        assertTrue(result.isSuccess)
        assertNull(fixture.repository.activeSession.value)
        assertEquals(listOf("fixture-a-first-visit"), fixture.guestExitTokens)
    }

    @Test fun reopeningAMinimizedHostKeepsItsMotorAndRevokesThePreviousRoute() = exercise { fixture, oldScreen ->
        startScreen(fixture, oldScreen)
        val original = fixture.repository.activeSession.value!!.sessionId
        val reopened = createScreen(fixture)
        val store = ViewModelStore().also { it.put("reopened", reopened) }
        try {
            reopened.host(fixture.stream.id); runCurrent()
            assertEquals(original, fixture.repository.activeSession.value?.sessionId)
            assertEquals("Reopening must not destroy and recreate the established motor", 1, fixture.managers.size)
            oldScreen.endLiveAndAwait()
            assertEquals("The previous route must not close the adopted background session", original,
                fixture.repository.activeSession.value?.sessionId)
        } finally { store.clear() }
    }

    @Test fun leavingBeforeTheQueuedRequestStartsCannotCreateAMotorLater() = exercise { fixture, screen ->
        screen.host(fixture.stream.id)
        assertTrue(screen.endLiveAndAwait().isSuccess)
        runCurrent()
        assertNull(fixture.repository.activeSession.value)
        assertTrue(fixture.managers.isEmpty())
        assertTrue("Cancel the route's child, not its caller", coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test fun disposingAPendingRouteCancelsItsLookupWithoutPublishingLater() = exercise { fixture, screen ->
        fixture.lookupRelease = CompletableDeferred()
        screen.host(fixture.stream.id); runCurrent()
        assertTrue(fixture.lookupEntered.isCompleted)
        ViewModelStore().also { it.put("disposed", screen) }.clear()
        runCurrent()
        fixture.lookupRelease!!.complete(Unit); runCurrent()
        assertNull(fixture.repository.activeSession.value)
        assertTrue(fixture.managers.isEmpty())
    }

    @Test fun repeatedAwaitedCloseStillWaitsForNativeReleaseAfterSessionDisappears() = exercise { fixture, screen ->
        startScreen(fixture, screen)
        var release: (() -> Unit)? = null
        every { fixture.managers.single().destroy(any()) } answers { release = firstArg(); Unit }
        screen.endLive(); runCurrent()
        assertNull(fixture.repository.activeSession.value)
        assertNotNull("The first close must have requested physical release", release)
        val secondClose = async { screen.endLiveAndAwait() }
        runCurrent()
        assertFalse("Clearing activeSession must not let a second waiter skip native draining", secondClose.isCompleted)
        release!!.invoke(); runCurrent()
        assertTrue(secondClose.isCompleted)
        // This fixture deliberately has no signaling ACK; the shared failure is preserved after draining.
        assertTrue(secondClose.await().isFailure)
        verify(exactly = 1) { fixture.managers.single().destroy(any()) }
    }

    @Test fun aRetainedMicrophoneActionCannotModifyTheIncomingMediaVisit() = exercise { fixture, oldScreen ->
        startScreen(fixture, oldScreen)
        replaceAccountVisit(fixture)
        val before = fixture.repository.isMicEnabled.value
        oldScreen.toggleMic(!before)
        assertEquals("A permission callback from the old route must not operate the new motor", before,
            fixture.repository.isMicEnabled.value)
    }

    @Test fun anOldScreenDoesNotExposeTheNewVisitThroughItsState() = exercise { fixture, oldScreen ->
        startScreen(fixture, oldScreen)
        replaceAccountVisit(fixture)
        assertNull("An inactive route must not render a different account/media visit", oldScreen.activeSession.value)
    }

    @Test fun theCurrentHostStillReceivesItsStateAndCanControlItsMicrophone() = exercise { fixture, screen ->
        startScreen(fixture, screen)
        assertEquals(fixture.repository.activeSession.value?.sessionId, screen.activeSession.value?.sessionId)
        screen.toggleMic(false)
        assertFalse(fixture.repository.isMicEnabled.value)
        screen.toggleMic(true)
        assertTrue(fixture.repository.isMicEnabled.value)
        assertTrue(screen.isMicEnabled.value)
    }

    @Test fun toolsRetainedByAPreviousRouteAreInvalidAfterMinimizedLiveIsReopened() = exercise { fixture, oldScreen ->
        startScreen(fixture, oldScreen)
        val oldTools = oldScreen.captureToolVisit(fixture.stream.id)
        assertTrue(oldTools.isCurrent)
        val reopened = createScreen(fixture)
        val store = ViewModelStore().also { it.put("reopened-tools", reopened) }
        try {
            reopened.host(fixture.stream.id); runCurrent()
            assertNotNull("The new route must really adopt the established media", reopened.activeSession.value)
            assertFalse(oldTools.isCurrent)
            var dispatched = false
            assertFalse(oldTools.run { dispatched = true })
            assertFalse(dispatched)
        } finally { store.clear() }
    }

    @Test fun clearingAnEstablishedScreenPreservesTheBackgroundSessionForReopening() = exercise { fixture, screen ->
        startScreen(fixture, screen)
        val id = fixture.repository.activeSession.value!!.sessionId
        ViewModelStore().also { it.put("minimized", screen) }.clear()
        runCurrent()
        assertEquals(id, fixture.repository.activeSession.value?.sessionId)
        verify(exactly = 0) { fixture.managers.single().destroy(any()) }
        val reopened = createScreen(fixture)
        val store = ViewModelStore().also { it.put("returned", reopened) }
        try {
            reopened.host(fixture.stream.id); runCurrent()
            assertEquals(id, reopened.activeSession.value?.sessionId)
            assertEquals(1, fixture.managers.size)
        } finally { store.clear() }
    }

    @Test fun aNewCollectorCannotReplayAnInvalidatedScreensSessionBeforeQueuedUpdatesRun() = exercise { fixture, screen ->
        startScreen(fixture, screen)
        assertNotNull(screen.activeSession.value)
        fixture.switchTo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "fixture-b-before-collection")
        // Deliberately do not advance the dispatcher: the synchronous read and replay must agree.
        assertNull(screen.activeSession.value)
        assertNull("A collector starting after invalidation must not replay old cached state", screen.activeSession.first())
    }

    @Test fun immediateCollectionSeesTheAdoptedMinimizedSessionWithoutAnUnrelatedStateChange() = exercise { fixture, originalScreen ->
        startScreen(fixture, originalScreen)
        val sessionId = fixture.repository.activeSession.value!!.sessionId
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val reopened = createScreen(fixture)
        val store = ViewModelStore().also { it.put("immediate-reopen", reopened) }
        val observed = mutableListOf<String?>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reopened.activeSession.collect { observed += it?.sessionId }
        }
        try {
            reopened.host(fixture.stream.id); runCurrent()
            assertEquals(sessionId, reopened.activeSession.value?.sessionId)
            assertEquals("Adoption itself must notify collectors even when the repository session is unchanged", sessionId, observed.last())
            assertEquals(1, fixture.managers.size)
        } finally { collection.cancel(); store.clear() }
    }

    @Test fun hostPreparationAdoptsItsSetupPreviewWithoutLosingTheChosenLens() = exercise(source = "camera") { fixture, screen ->
        assertTrue(fixture.repository.startSetupCameraPreview().isSuccess)
        assertTrue(fixture.repository.switchSetupCameraPreview())
        assertFalse(fixture.repository.isFrontCamera.value)
        screen.prepareHost(fixture.stream.id)
        assertFalse("The route must expose its adopted rear-camera preview", screen.isFrontCamera.value)
        assertNull(screen.activeSession.value)
        screen.host(fixture.stream.id); runCurrent()
        assertNotNull(screen.activeSession.value)
        assertFalse(screen.isFrontCamera.value)
        assertEquals(1, fixture.managers.size)
    }

    @Test fun releasingAnUnusedPreviewForPermissionRetryDoesNotCloseTheScreenReceipt() = exercise(source = "camera") { fixture, screen ->
        assertTrue(fixture.repository.startSetupCameraPreview().isSuccess)
        screen.prepareHost(fixture.stream.id); runCurrent()
        screen.releaseUnusedSetupPreview(); runCurrent()
        coVerify(exactly = 1) { fixture.managers.single().stopVideoAwait() }
        screen.host(fixture.stream.id); runCurrent()
        assertNotNull("Granting permission later must still start this screen's session", screen.activeSession.value)
        assertEquals(1, fixture.managers.size)
    }

    @Test fun unusedPreviewCleanupFromAnOldRouteCannotStopTheReopenedRoutesPreview() = exercise(source = "camera") { fixture, oldScreen ->
        assertTrue(fixture.repository.startSetupCameraPreview().isSuccess)
        oldScreen.prepareHost(fixture.stream.id); runCurrent()
        val reopened = createScreen(fixture)
        val store = ViewModelStore().also { it.put("reopened-preview", reopened) }
        try {
            reopened.prepareHost(fixture.stream.id); runCurrent()
            oldScreen.releaseUnusedSetupPreview(); runCurrent()
            coVerify(exactly = 0) { fixture.managers.single().stopVideoAwait() }
        } finally { store.clear() }
    }

    @Test fun preparingAnotherRouteCannotAdoptAPreviewThatIsStillStartingItsHost() = exercise(source = "camera") { fixture, oldScreen ->
        assertTrue(fixture.repository.startSetupCameraPreview().isSuccess)
        fixture.lookupRelease = CompletableDeferred()
        oldScreen.host(fixture.stream.id); runCurrent()
        assertTrue(fixture.lookupEntered.isCompleted)
        assertNull(fixture.repository.activeSession.value)
        val reopened = createScreen(fixture)
        val store = ViewModelStore().also { it.put("during-startup", reopened) }
        try {
            reopened.prepareHost(fixture.stream.id); runCurrent()
            oldScreen.leaveLive(); runCurrent()
            fixture.lookupRelease!!.complete(Unit); runCurrent()
            reopened.host(fixture.stream.id); runCurrent()
            assertNotNull("The new route must not retain the canceled startup's released motor", reopened.activeSession.value)
            assertEquals(2, fixture.managers.size)
        } finally { store.clear() }
    }

    @Test fun closingAPublishedVideoKeepsTheRendererDetachWaitAfterSessionStateIsCleared() = exercise { fixture, screen ->
        startScreen(fixture, screen)
        fixture.remoteTrackStates.single().value = mapOf("host" to mockk<org.webrtc.VideoTrack>(relaxed = true))
        runCurrent()
        assertTrue(screen.remoteVideoTracks.value.isNotEmpty())
        val closing = async { screen.endLiveAndAwait() }
        runCurrent()
        assertNull(fixture.repository.activeSession.value)
        verify(exactly = 0) { fixture.managers.single().destroy(any()) }
        advanceTimeBy(95); runCurrent()
        assertFalse(closing.isCompleted)
        verify(exactly = 0) { fixture.managers.single().destroy(any()) }
        advanceTimeBy(1); runCurrent()
        // The fixture has no server ACK; native draining must precede that shared failure.
        assertTrue(closing.await().isFailure)
        verify(exactly = 1) { fixture.managers.single().destroy(any()) }
    }

    @Test fun anAudioOnlyCloseDoesNotAddARendererDetachWait() = exercise { fixture, screen ->
        startScreen(fixture, screen)
        val before = testScheduler.currentTime
        assertTrue(screen.endLiveAndAwait().isFailure)
        assertEquals(before, testScheduler.currentTime)
        verify(exactly = 1) { fixture.managers.single().destroy(any()) }
    }
}
