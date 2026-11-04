package conectate.com.presentation.video.routes

import androidx.lifecycle.ViewModelStore
import conectate.com.core.network.InteractiveReadDeadlines
import conectate.com.data.model.Video
import conectate.com.data.supabase.AuthSessionMutationGuard
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoRoutesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val video = Video(id = "Video-A", userId = "author", title = "Guitarra", videoUrl = "https://example.test/a.mp4")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private inner class Fixture {
        val guard = AuthSessionMutationGuard()
        val changes = MutableStateFlow(0)
        var account: String? = "alice"
        val stored = mutableMapOf<String, VideoRoute>()
        var queries = 0
        var writes = 0
        var failRead = false
        var failWrite = false
        var search: suspend (String) -> Result<List<Video>> = { Result.success(listOf(video)) }
        var afterWrite: suspend () -> Unit = {}
        val vm = VideoRoutesViewModel(
            captureAccount = { guard.captureAccountLease { account } },
            accountChanges = changes,
            search = { queries++; search(it) },
            readStored = { lease ->
                lease.requireCurrent()
                if (failRead) throw IOException("read failed")
                stored[lease.accountId]
            },
            replaceStored = { lease, expected, next ->
                if (failWrite) throw IOException("disk full")
                val accepted = guard.commitForLease(lease) {
                    val current = stored[lease.accountId]
                    check(current?.id == expected?.id && current?.revision == expected?.revision)
                    if (next == null) stored.remove(lease.accountId) else stored[checkNotNull(lease.accountId)] = next
                    writes++
                }
                check(accepted)
                afterWrite()
            },
            eraseStored = { lease ->
                check(guard.commitForLease(lease) { stored.remove(lease.accountId) })
            },
        ).also { viewModels.put("routes", it) }

        fun switchAccount(next: String?) {
            guard.invalidate { account = next }
            changes.value++
        }
    }

    @Test fun preparationOnlyRunsAfterTheUserRequestsItAndStoresTheRealSelection() = runTest(dispatcher) {
        val fixture = Fixture()
        advanceUntilIdle()
        assertEquals(0, fixture.queries)
        fixture.vm.changeQuery(" guitarra ")
        fixture.vm.changeSize(5)
        fixture.vm.prepare()
        advanceUntilIdle()
        val state = fixture.vm.state.value
        assertFalse(state.busy)
        assertFalse(state.composerVisible)
        assertEquals("guitarra", state.route?.topic)
        assertEquals(1, state.route?.items?.size)
        assertEquals(5, state.route?.requestedCount)
        assertEquals(state.route, fixture.stored["alice"])
    }

    @Test fun blankQueriesNeverUseTheNetwork() = runTest(dispatcher) {
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.vm.prepare()
        advanceUntilIdle()
        assertEquals(0, fixture.queries)
        assertNotNull(fixture.vm.state.value.error)
    }

    @Test fun emptyAndFailedSearchesPreserveThePreviousSelectionAndInput() = runTest(dispatcher) {
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.vm.changeQuery("guitarra")
        fixture.vm.prepare()
        advanceUntilIdle()
        val previous = fixture.vm.state.value.route
        fixture.vm.showComposer()
        fixture.vm.changeQuery("otro tema")
        fixture.search = { Result.success(emptyList()) }
        fixture.vm.prepare()
        advanceUntilIdle()
        assertEquals(previous, fixture.stored["alice"])
        assertEquals(previous, fixture.vm.state.value.route)
        assertEquals("otro tema", fixture.vm.state.value.query)
        fixture.search = { Result.failure(IOException("HTTP details must stay private")) }
        fixture.vm.prepare()
        advanceUntilIdle()
        assertEquals(previous, fixture.vm.state.value.route)
        assertFalse(fixture.vm.state.value.error.orEmpty().contains("HTTP"))
    }

    @Test fun aSlowSearchHasOneDeadlineAndRepeatedTapsDoNotDuplicateIt() = runTest(dispatcher) {
        val fixture = Fixture()
        val response = CompletableDeferred<Result<List<Video>>>()
        fixture.search = { response.await() }
        advanceUntilIdle()
        fixture.vm.changeQuery("guitarra")
        fixture.vm.prepare()
        fixture.vm.prepare()
        runCurrent()
        assertEquals(1, fixture.queries)
        advanceTimeBy(InteractiveReadDeadlines.FirstPageMillis + 1)
        runCurrent()
        assertFalse(fixture.vm.state.value.busy)
        assertNotNull(fixture.vm.state.value.error)
        assertNull(fixture.stored["alice"])
    }

    @Test fun anOpeningIsPersistedBeforeNavigatingWithItsOriginalId() = runTest(dispatcher) {
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.vm.changeQuery("guitarra")
        fixture.vm.prepare()
        advanceUntilIdle()
        var opened: String? = null
        fixture.vm.openVideo("video-a") { id ->
            assertTrue(fixture.stored["alice"]!!.items.single().opened)
            opened = id
        }
        advanceUntilIdle()
        assertEquals("Video-A", opened)
        fixture.vm.toggleUseful("video-a")
        advanceUntilIdle()
        assertEquals(1, fixture.stored["alice"]?.usefulCount)
    }

    @Test fun aFailedOpeningWriteDoesNotNavigateOrClaimItWasOpened() = runTest(dispatcher) {
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.vm.changeQuery("guitarra")
        fixture.vm.prepare()
        advanceUntilIdle()
        fixture.failWrite = true
        var opened = false
        fixture.vm.openVideo("video-a") { opened = true }
        advanceUntilIdle()
        assertFalse(opened)
        assertEquals(0, fixture.vm.state.value.route?.openedCount)
        assertNotNull(fixture.vm.state.value.error)
        assertFalse(fixture.vm.state.value.busy)
    }

    @Test fun aLateSearchCannotWriteAfterSwitchingAwayAndBackToTheSameAccount() = runTest(dispatcher) {
        val fixture = Fixture()
        val response = CompletableDeferred<Result<List<Video>>>()
        fixture.search = { withContext(NonCancellable) { response.await() } }
        advanceUntilIdle()
        fixture.vm.changeQuery("private goal")
        fixture.vm.prepare()
        runCurrent()
        fixture.switchAccount("bob")
        runCurrent()
        assertEquals("", fixture.vm.state.value.query)
        fixture.switchAccount("alice")
        runCurrent()
        response.complete(Result.success(listOf(video)))
        advanceUntilIdle()
        assertEquals(0, fixture.writes)
        assertNull(fixture.vm.state.value.route)
        assertEquals("", fixture.vm.state.value.query)
    }

    @Test fun anAlreadyWrittenOldAccountAckCannotNavigateAfterTheAccountChanges() = runTest(dispatcher) {
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.vm.changeQuery("guitarra")
        fixture.vm.prepare()
        advanceUntilIdle()
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.afterWrite = { written.complete(Unit); withContext(NonCancellable) { release.await() } }
        var opened = false
        fixture.vm.openVideo("video-a") { opened = true }
        runCurrent()
        assertTrue(written.isCompleted)
        fixture.switchAccount("bob")
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(opened)
        assertEquals("bob", fixture.vm.state.value.accountId)
        assertNull(fixture.vm.state.value.route)
        assertTrue(fixture.stored["alice"]!!.items.single().opened)
    }

    @Test fun conclusionsStayInMemoryUntilSavedAndFinishingPersistsTheChosenResult() = runTest(dispatcher) {
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.vm.changeQuery("guitarra")
        fixture.vm.prepare()
        advanceUntilIdle()
        fixture.vm.changeConclusion("Practicar mañana")
        assertEquals("", fixture.stored["alice"]?.conclusion)
        fixture.vm.setCompleted(true)
        advanceUntilIdle()
        assertEquals("Practicar mañana", fixture.stored["alice"]?.conclusion)
        assertTrue(fixture.stored["alice"]!!.completed)
        fixture.vm.setCompleted(false)
        advanceUntilIdle()
        assertFalse(fixture.stored["alice"]!!.completed)
    }

    @Test fun restoreFailureRequiresExplicitRecoveryAndEraseDoesNotAffectAnotherAccount() = runTest(dispatcher) {
        val fixture = Fixture()
        fixture.failRead = true
        fixture.stored["bob"] = prepareVideoRoute("otro", 3, listOf(video))!!
        advanceUntilIdle()
        assertTrue(fixture.vm.state.value.storageBlocked)
        fixture.vm.changeQuery("guitarra")
        fixture.vm.prepare()
        assertEquals(0, fixture.queries)
        fixture.vm.erase()
        advanceUntilIdle()
        assertFalse(fixture.vm.state.value.storageBlocked)
        assertEquals("", fixture.vm.state.value.query)
        assertNotNull(fixture.stored["bob"])
    }

    @Test fun logoutClearsPrivateDraftsAndRestoringTheOwnerReloadsTheSavedRoute() = runTest(dispatcher) {
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.vm.changeQuery("guitarra")
        fixture.vm.prepare()
        advanceUntilIdle()
        val saved = fixture.vm.state.value.route
        fixture.vm.changeConclusion("unsaved private draft")
        fixture.switchAccount(null)
        advanceUntilIdle()
        assertEquals("", fixture.vm.state.value.conclusion)
        assertNull(fixture.vm.state.value.route)
        fixture.switchAccount("alice")
        advanceUntilIdle()
        assertEquals(saved, fixture.vm.state.value.route)
        assertEquals("", fixture.vm.state.value.conclusion)
    }
}
