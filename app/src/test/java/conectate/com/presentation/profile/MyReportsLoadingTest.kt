package conectate.com.presentation.profile

import android.util.Log
import androidx.lifecycle.ViewModelStore
import conectate.com.data.model.Report
import conectate.com.data.model.VideoReport
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthResponse
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MyReportsLoadingTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val guard = AuthSessionMutationGuard()
    private var account: String? = "a"
    private val auth = mockk<AuthService>()
    private val messages = mockk<MessageRepository>()
    private val videos = mockk<VideoRepository>()
    private lateinit var model: MyReportsViewModel
    private fun report(id: String) = Report(id = id, reporter_id = account)
    private fun video(id: String) = VideoReport(id, "video", "owner", reporterId = account ?: "", reason = "QA", createdAt = "2026-11-17T12:00:00Z", updatedAt = "2026-11-17T12:00:00Z")

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        every { auth.getCurrentUserId() } answers { account }
        every { auth.observeSession() } returns MutableStateFlow<AuthResponse?>(null)
        every { auth.accountVisitChanges } returns guard.accountVisitChanges
        every { auth.commitForAccountLease(any(), any()) } answers { guard.commitForLease(firstArg(), secondArg()) }
        coEvery { auth.ensureValidAccessToken() } returns Result.success("token-a")
        coEvery { messages.getMyReports() } returns Result.success(emptyList())
        coEvery { videos.getMyVideoReports() } returns Result.success(emptyList())
    }
    private fun create() {
        // The same behavior cases compile against the pre-patch and injected-session constructors.
        val constructor = MyReportsViewModel::class.java.constructors.single { !it.isSynthetic }
        val args = if (constructor.parameterCount == 2) arrayOf(messages, videos) else arrayOf(messages, videos, auth)
        model = constructor.newInstance(*args) as MyReportsViewModel
        store.put("reports", model)
    }
    @After fun stop() { store.clear(); unmockkStatic(Log::class); Dispatchers.resetMain() }
    private fun warning(state: MyReportsUiState): String? = state.javaClass.methods
        .firstOrNull { it.name == "getWarning" }?.invoke(state) as? String

    @Test fun aFailedUserQueryCannotClaimThatBothReportListsAreEmpty() = runTest(dispatcher) {
        coEvery { messages.getMyReports() } returns Result.failure(IllegalStateException("offline"))
        create(); runCurrent()
        val state = model.uiState.value
        assertTrue("A failed source must be an error or a visible partial warning", state is MyReportsUiState.Error || warning(state) != null)
    }
    @Test fun aFailedVideoQueryCannotClaimThatBothReportListsAreEmpty() = runTest(dispatcher) {
        coEvery { videos.getMyVideoReports() } returns Result.failure(IllegalStateException("offline"))
        create(); runCurrent()
        val state = model.uiState.value
        assertTrue(state is MyReportsUiState.Error || warning(state) != null)
    }
    @Test fun aPartialResultKeepsItsAvailableItemsAndIdentifiesTheMissingSource() = runTest(dispatcher) {
        coEvery { messages.getMyReports() } returns Result.success(listOf(report("kept")))
        coEvery { videos.getMyVideoReports() } returns Result.failure(IllegalStateException("offline"))
        create(); runCurrent()
        val state = model.uiState.value as MyReportsUiState.Success
        assertEquals("kept", state.reports.single().id)
        assertTrue(warning(state).orEmpty().contains("video", true))
    }
    @Test fun twoConfirmedEmptyListsRemainAnOrdinaryEmptySuccess() = runTest(dispatcher) {
        create(); runCurrent()
        val state = model.uiState.value as MyReportsUiState.Success
        assertTrue(state.reports.isEmpty() && state.videoReports.isEmpty()); assertNull(warning(state))
    }
    @Test fun aSlowUserQueryDoesNotPreventTheVideoQueryFromStarting() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<List<Report>>>()
        var videoStarted = false
        coEvery { messages.getMyReports() } coAnswers { response.await() }
        coEvery { videos.getMyVideoReports() } coAnswers { videoStarted = true; Result.success(emptyList()) }
        create(); runCurrent()
        try { assertTrue("Both independent sources must start", videoStarted) }
        finally { response.complete(Result.success(emptyList())); runCurrent() }
    }
    @Test fun aLateOlderRefreshCannotOverwriteTheNewerResult() = runTest(dispatcher) {
        val first = CompletableDeferred<Result<List<Report>>>()
        var calls = 0
        coEvery { messages.getMyReports() } coAnswers {
            if (++calls == 1) withContext(NonCancellable) { first.await() } else Result.success(listOf(report("new")))
        }
        create(); runCurrent(); model.loadMyReports(); runCurrent()
        first.complete(Result.success(listOf(report("old")))); runCurrent()
        assertEquals("new", (model.uiState.value as MyReportsUiState.Success).reports.single().id)
    }
    @Test fun changingAccountClearsAlreadyRenderedPrivateReports() = runTest(dispatcher) {
        coEvery { messages.getMyReports() } coAnswers { Result.success(listOf(report(account ?: "none"))) }
        create(); runCurrent(); assertEquals("a", (model.uiState.value as MyReportsUiState.Success).reports.single().id)
        guard.invalidate { account = "b" }; runCurrent()
        assertEquals("b", (model.uiState.value as MyReportsUiState.Success).reports.single().id)
    }
    @Test fun signingOutRemovesTheOutgoingAccountsPrivateReports() = runTest(dispatcher) {
        coEvery { messages.getMyReports() } returns Result.success(listOf(report("private-a")))
        create(); runCurrent(); guard.invalidate { account = null }; runCurrent()
        assertFalse(model.uiState.value is MyReportsUiState.Success)
    }
    @Test fun returningToTheSameAccountDoesNotRestoreAnOldPendingResponse() = runTest(dispatcher) {
        val first = CompletableDeferred<Result<List<Report>>>()
        var calls = 0
        coEvery { messages.getMyReports() } coAnswers {
            if (++calls == 1) withContext(NonCancellable) { first.await() } else Result.success(listOf(report("new-visit")))
        }
        create(); runCurrent(); guard.invalidate { account = "b" }; guard.invalidate { account = "a" }; runCurrent()
        first.complete(Result.success(listOf(report("old-visit")))); runCurrent()
        assertEquals("new-visit", (model.uiState.value as MyReportsUiState.Success).reports.single().id)
    }
    @Test fun anUnresponsiveSourceStopsLoadingAndLeavesTheOtherSourceAvailable() = runTest(dispatcher) {
        coEvery { messages.getMyReports() } coAnswers { awaitCancellation() }
        coEvery { videos.getMyVideoReports() } returns Result.success(listOf(video("kept-video")))
        create(); runCurrent(); advanceTimeBy(15001); runCurrent()
        val state = model.uiState.value
        assertFalse("A request cannot keep the screen loading indefinitely", state is MyReportsUiState.Loading)
        assertEquals("kept-video", (state as MyReportsUiState.Success).videoReports.single().id)
        assertNotNull(warning(state))
    }
    @Test fun cancellingTheScreenDoesNotPublishAnUnexpectedError() = runTest(dispatcher) {
        coEvery { messages.getMyReports() } coAnswers { awaitCancellation() }
        create(); runCurrent(); store.clear(); runCurrent()
        assertFalse(model.uiState.value is MyReportsUiState.Error)
    }
    @Test fun bothFailedSourcesShowARetryableError() = runTest(dispatcher) {
        coEvery { messages.getMyReports() } returns Result.failure(IllegalStateException("users unavailable"))
        coEvery { videos.getMyVideoReports() } returns Result.failure(IllegalStateException("videos unavailable"))
        create(); runCurrent(); assertTrue(model.uiState.value is MyReportsUiState.Error)
    }
    @Test fun aRetryReplacesThePartialWarningWithBothConfirmedLists() = runTest(dispatcher) {
        coEvery { videos.getMyVideoReports() } returns Result.failure(IllegalStateException("offline"))
        create(); runCurrent(); assertNotNull(warning(model.uiState.value))
        coEvery { videos.getMyVideoReports() } returns Result.success(listOf(video("recovered")))
        model.loadMyReports(); runCurrent()
        val state = model.uiState.value as MyReportsUiState.Success
        assertNull(warning(state)); assertEquals("recovered", state.videoReports.single().id)
    }
    @Test fun credentialRenewalHasTheSameLoadingDeadlineAsTheRequests() = runTest(dispatcher) {
        coEvery { auth.ensureValidAccessToken() } coAnswers { awaitCancellation() }
        create(); runCurrent(); advanceTimeBy(15001); runCurrent()
        assertTrue(model.uiState.value is MyReportsUiState.Error)
        coVerify(exactly = 0) { messages.getMyReports() }
        coVerify(exactly = 0) { videos.getMyVideoReports() }
    }
}
