package conectate.com.presentation.messages

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import conectate.com.data.groupvoice.*
import conectate.com.data.repository.GroupVoiceRepository
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupVoiceRoomViewModelTest {
    private val guard = AuthSessionMutationGuard()
    private val repository = mockk<GroupVoiceRepository>()
    private val visits = MutableStateFlow(guard.captureAccountLease { "a" })
    private val roster = GroupVoiceRoster("group", "Grupo", listOf(GroupVoiceMember("b", "B"), GroupVoiceMember("c", "C")))
    init {
        every { repository.capture() } answers { guard.captureAccountLease { "a" } }
        every { repository.visits() } returns visits
        every { repository.publish(any(), any()) } answers {
            val lease = firstArg<conectate.com.data.supabase.AuthSessionLease>()
            guard.commitForLease(lease, secondArg())
        }
        coEvery { repository.pending(any(), any()) } returns null
        coEvery { repository.roster(any(), any()) } returns Result.success(roster)
        coEvery { repository.create(any(), any(), any()) } returns Result.success(GroupVoiceCreated("room", 2))
    }
    private suspend fun TestScope.withVm(block: suspend (GroupVoiceRoomViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST") return GroupVoiceRoomViewModel(repository) as T
            }
        })[GroupVoiceRoomViewModel::class.java]
        try { block(vm) } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun theInitialVisitDoesNotCancelLoadingAndAllUsesTheAuthoritativeCount() = runTest { withVm { vm ->
        vm.open("group"); runCurrent()
        assertFalse(vm.state.value.loading); assertFalse(vm.state.value.sessionChanged)
        assertEquals(2, vm.state.value.count)
    } }
    @Test fun individualAndSelectAllControlsKeepTheCountInSync() = runTest { withVm { vm ->
        vm.open("group"); runCurrent(); vm.mode("selected")
        assertEquals(0, vm.state.value.count)
        vm.toggle("b"); assertEquals(1, vm.state.value.count)
        vm.all(); assertEquals(2, vm.state.value.count)
        vm.all(); assertEquals(0, vm.state.value.count)
    } }
    @Test fun twoImmediateClicksCreateOnlyOneInvitation() = runTest { withVm { vm ->
        val response = CompletableDeferred<Result<GroupVoiceCreated>>()
        coEvery { repository.create(any(), any(), any()) } coAnswers { response.await() }
        vm.open("group"); runCurrent(); vm.create(); vm.create(); runCurrent()
        coVerify(exactly = 1) { repository.create(any(), "group", any()) }
        response.complete(Result.success(GroupVoiceCreated("room", 2))); runCurrent()
        assertEquals("room", vm.state.value.roomId); assertFalse(vm.state.value.creating)
    } }
    @Test fun aClosedDialogDoesNotNavigateWhenAnUncancellableReplyArrives() = runTest { withVm { vm ->
        val response = CompletableDeferred<Result<GroupVoiceCreated>>()
        coEvery { repository.create(any(), any(), any()) } coAnswers { withContext(NonCancellable) { response.await() } }
        vm.open("group"); runCurrent(); vm.create(); runCurrent(); vm.close(); vm.open("other"); runCurrent()
        response.complete(Result.success(GroupVoiceCreated("old-room", 2))); runCurrent()
        assertNull(vm.state.value.roomId); assertFalse(vm.state.value.creating)
    } }
    @Test fun anAccountVisitChangeClosesTheDialogAndCancelsTheCommand() = runTest { withVm { vm ->
        vm.open("group"); runCurrent()
        guard.invalidate { }; visits.value = guard.captureAccountLease { "a" }; runCurrent()
        assertTrue(vm.state.value.sessionChanged)
        vm.create(); runCurrent(); coVerify(exactly = 0) { repository.create(any(), any(), any()) }
    } }
    @Test fun anExistingUnconfirmedIntentIsImmutableAndReused() = runTest { withVm { vm ->
        val saved = GroupVoiceIntent("same-id", "selected", listOf("b"))
        coEvery { repository.pending(any(), any()) } returns saved
        vm.open("group"); runCurrent(); vm.toggle("c"); vm.mode("all")
        assertEquals("selected", vm.state.value.mode); assertEquals(1, vm.state.value.count)
        vm.create(); runCurrent(); coVerify { repository.create(any(), "group", saved) }
    } }
    @Test fun rosterTimeoutReleasesLoadingAndShowsAnError() = runTest { withVm { vm ->
        coEvery { repository.roster(any(), any()) } coAnswers { withTimeout(20000) { awaitCancellation() } }
        vm.open("group"); advanceTimeBy(20001); runCurrent()
        assertFalse(vm.state.value.loading); assertNotNull(vm.state.value.error)
    } }
    @Test fun confirmationTimeoutReleasesTheButtonAndKeepsTheIntentForRetry() = runTest { withVm { vm ->
        coEvery { repository.create(any(), any(), any()) } coAnswers { withTimeout(20000) { awaitCancellation() } }
        vm.open("group"); runCurrent(); vm.create(); advanceTimeBy(20001); runCurrent()
        assertFalse(vm.state.value.creating); assertTrue(vm.state.value.pending); assertNotNull(vm.state.value.error)
    } }
}
