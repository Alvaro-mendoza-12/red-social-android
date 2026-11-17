package conectate.com.presentation.video

import androidx.lifecycle.ViewModelStore
import conectate.com.data.localfallback.*
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthResponse
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.UserData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercise real ViewModels with controlled server replies, including replies after a confirmed delete. */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoLibraryLoadingTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = mockk<VideoRepository>()
    private val auth = mockk<AuthService>()
    private val realtime = mockk<UbuntuRealtimeClient>(relaxed = true)
    private val events = MutableSharedFlow<UbuntuRealtimeEnvelope>(extraBufferCapacity = 8)
    private lateinit var history: VideoHistoryViewModel
    private lateinit var later: VideoWatchLaterViewModel
    private lateinit var collections: VideoCollectionsViewModel

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        val session = AuthResponse("token", "refresh", "bearer", 3600, UserData("owner", "qa@example.invalid"))
        every { auth.getCurrentUserId() } returns "owner"
        every { auth.observeSession() } returns MutableStateFlow<AuthResponse?>(session)
        coEvery { auth.ensureValidAccessToken() } returns Result.success("token")
        every { realtime.events } returns events
        history = VideoHistoryViewModel(repository, auth, realtime)
        later = VideoWatchLaterViewModel(repository, auth, realtime)
        collections = VideoCollectionsViewModel(repository, auth, realtime)
        store.put("history", history)
        store.put("later", later)
        store.put("collections", collections)
    }

    @After fun stop() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun historyPage(vararg ids: String, cursor: String? = null) =
        VideoHistoryPage(ids.map { VideoHistoryItem(videoId = it) }, cursor)
    private fun laterPage(vararg ids: String, cursor: String? = null) =
        VideoWatchLaterPage(ids.map { VideoWatchLaterItem(videoId = it) }, cursor)
    private fun collectionPage(vararg ids: String, offset: Int? = null) =
        VideoCollectionPage(ids.map { VideoCollectionItem(videoId = it) }, nextOffset = offset)

    private fun TestScope.openCollection() {
        coEvery { repository.getVideoCollections() } returns Result.success(
            VideoCollectionCatalog(listOf(VideoCollection(id = "list", name = "My list"))))
        coEvery { repository.getVideoCollectionItems("list", "", 0) } returns
            Result.success(collectionPage("first", offset = 30))
        runCurrent()
        collections.refresh(); runCurrent()
        collections.select("list"); runCurrent()
    }

    @Test fun deletedHistoryVideoCannotReturnFromAnOlderPage() = runTest(dispatcher) {
        val oldPage = CompletableDeferred<Result<VideoHistoryPage>>()
        coEvery { repository.getVideoHistory("") } returns Result.success(historyPage("removed", "kept", cursor = "next"))
        coEvery { repository.getVideoHistory("next") } coAnswers { oldPage.await() }
        coEvery { repository.deleteVideoHistory("removed") } returns Result.success(Unit)
        runCurrent(); history.refresh(); runCurrent()
        history.loadMore(); runCurrent()
        coEvery { repository.getVideoHistory("") } returns Result.success(historyPage("kept"))
        history.remove("removed"); runCurrent()
        oldPage.complete(Result.success(historyPage("removed", "old-tail"))); runCurrent()
        assertEquals(listOf("kept"), history.state.value.items.map { it.videoId })
        assertFalse(history.state.value.loading)
    }

    @Test fun clearedHistoryCannotReturnFromAnOlderRefresh() = runTest(dispatcher) {
        val oldPage = CompletableDeferred<Result<VideoHistoryPage>>()
        coEvery { repository.getVideoHistory("") } coAnswers { oldPage.await() }
        coEvery { repository.deleteVideoHistory(null) } returns Result.success(Unit)
        runCurrent(); history.refresh(); runCurrent()
        history.remove(null); runCurrent()
        oldPage.complete(Result.success(historyPage("removed"))); runCurrent()
        assertTrue(history.state.value.items.isEmpty())
        assertNull(history.state.value.nextCursor)
        assertFalse(history.state.value.loading)
    }

    @Test fun refreshingHistoryDoesNotUnlockAnUnfinishedDelete() = runTest(dispatcher) {
        val deletion = CompletableDeferred<Result<Unit>>()
        coEvery { repository.getVideoHistory("") } returns Result.success(historyPage("first"))
        coEvery { repository.deleteVideoHistory("first") } coAnswers { deletion.await() }
        runCurrent(); history.refresh(); runCurrent()
        history.remove("first"); runCurrent()
        history.refresh(); runCurrent()
        assertTrue(history.state.value.deleting)
        history.remove("first"); runCurrent()
        coVerify(exactly = 1) { repository.deleteVideoHistory("first") }
        deletion.complete(Result.success(Unit)); runCurrent()
    }

    @Test fun failedHistoryDeleteKeepsTheVideoAndAllowsRetry() = runTest(dispatcher) {
        coEvery { repository.getVideoHistory("") } returns Result.success(historyPage("kept"))
        coEvery { repository.deleteVideoHistory("kept") } returns Result.failure(Exception("offline"))
        runCurrent(); history.refresh(); runCurrent(); history.remove("kept"); runCurrent()
        assertEquals(listOf("kept"), history.state.value.items.map { it.videoId })
        assertEquals("offline", history.state.value.error)
        assertFalse(history.state.value.deleting)
    }

    @Test fun historyPaginationStillAppendsDistinctVideos() = runTest(dispatcher) {
        coEvery { repository.getVideoHistory("") } returns Result.success(historyPage("first", cursor = "next"))
        coEvery { repository.getVideoHistory("next") } returns Result.success(historyPage("first", "second"))
        runCurrent(); history.refresh(); runCurrent(); history.loadMore(); runCurrent()
        assertEquals(listOf("first", "second"), history.state.value.items.map { it.videoId })
        assertNull(history.state.value.nextCursor)
    }

    @Test fun removedWatchLaterVideoCannotReturnFromAnOlderPage() = runTest(dispatcher) {
        val oldPage = CompletableDeferred<Result<VideoWatchLaterPage>>()
        coEvery { repository.getVideoWatchLater("", "newest") } returns Result.success(laterPage("removed", "kept", cursor = "next"))
        coEvery { repository.getVideoWatchLater("next", "newest") } coAnswers { oldPage.await() }
        coEvery { repository.setVideoWatchLater("removed", false) } returns Result.success(Unit)
        runCurrent(); later.refresh(); runCurrent(); later.loadMore(); runCurrent()
        coEvery { repository.getVideoWatchLater("", "newest") } returns Result.success(laterPage("kept"))
        later.remove("removed"); runCurrent()
        oldPage.complete(Result.success(laterPage("removed", "old-tail"))); runCurrent()
        assertEquals(listOf("kept"), later.state.value.items.map { it.videoId })
        assertFalse(later.state.value.loading)
    }

    @Test fun failedWatchLaterRemovalKeepsTheVideoAndAllowsRetry() = runTest(dispatcher) {
        coEvery { repository.getVideoWatchLater("", "newest") } returns Result.success(laterPage("kept"))
        coEvery { repository.setVideoWatchLater("kept", false) } returns Result.failure(Exception("offline"))
        runCurrent(); later.refresh(); runCurrent(); later.remove("kept"); runCurrent()
        later.remove("kept"); runCurrent()
        assertEquals(listOf("kept"), later.state.value.items.map { it.videoId })
        assertEquals("offline", later.state.value.error)
        coVerify(exactly = 2) { repository.setVideoWatchLater("kept", false) }
    }

    @Test fun watchLaterSortChangeRejectsTheOldSortResponse() = runTest(dispatcher) {
        val newest = CompletableDeferred<Result<VideoWatchLaterPage>>()
        coEvery { repository.getVideoWatchLater("", "newest") } coAnswers { newest.await() }
        coEvery { repository.getVideoWatchLater("", "oldest") } returns Result.success(laterPage("oldest"))
        runCurrent(); later.refresh(); runCurrent(); later.setSort("oldest"); runCurrent()
        newest.complete(Result.success(laterPage("newest"))); runCurrent()
        assertEquals("oldest", later.state.value.sort)
        assertEquals(listOf("oldest"), later.state.value.items.map { it.videoId })
    }

    @Test fun aNewCollectionSearchClearsThePreviousPageAndCursorImmediately() = runTest(dispatcher) {
        openCollection()
        collections.search("cats")
        assertTrue(collections.state.value.videos.isEmpty())
        assertNull(collections.state.value.nextOffset)
        assertTrue(collections.state.value.loadingVideos)
    }

    @Test fun aNewCollectionSearchCannotStartAtThePreviousQueriesOffset() = runTest(dispatcher) {
        openCollection()
        coEvery { repository.getVideoCollectionItems("list", "cats", 30) } returns Result.success(collectionPage("wrong-page"))
        coEvery { repository.getVideoCollectionItems("list", "cats", 0) } returns Result.success(collectionPage("cat"))
        collections.search("cats"); collections.loadMore(); runCurrent()
        coVerify(exactly = 0) { repository.getVideoCollectionItems("list", "cats", 30) }
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf("cat"), collections.state.value.videos.map { it.videoId })
    }

    @Test fun returningToTheSameSearchTextDoesNotAdmitItsOldPage() = runTest(dispatcher) {
        openCollection()
        val oldPage = CompletableDeferred<Result<VideoCollectionPage>>()
        coEvery { repository.getVideoCollectionItems("list", "", 30) } coAnswers { oldPage.await() }
        collections.loadMore(); runCurrent()
        collections.search("cats"); collections.search("")
        oldPage.complete(Result.success(collectionPage("old-tail"))); runCurrent()
        assertTrue(collections.state.value.videos.isEmpty())
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf("first"), collections.state.value.videos.map { it.videoId })
    }

    @Test fun deletingTheOpenCollectionClearsItsVideosCursorAndSpinner() = runTest(dispatcher) {
        openCollection()
        val oldPage = CompletableDeferred<Result<VideoCollectionPage>>()
        val catalog = CompletableDeferred<Result<VideoCollectionCatalog>>()
        coEvery { repository.getVideoCollectionItems("list", "", 30) } coAnswers { oldPage.await() }
        coEvery { repository.deleteVideoCollection("list") } returns Result.success(Unit)
        coEvery { repository.getVideoCollections() } coAnswers { catalog.await() }
        collections.loadMore(); runCurrent(); collections.delete("list"); runCurrent()
        assertNull(collections.state.value.selectedId)
        assertTrue(collections.state.value.videos.isEmpty())
        assertNull(collections.state.value.nextOffset)
        assertFalse(collections.state.value.loadingVideos)
        assertTrue(collections.state.value.collections.isEmpty())
        oldPage.complete(Result.success(collectionPage("old-tail")))
        catalog.complete(Result.success(VideoCollectionCatalog())); runCurrent()
        assertFalse(collections.state.value.loadingVideos)
    }

    @Test fun aCollectionRemovedElsewhereDoesNotLeaveItsSpinnerRunning() = runTest(dispatcher) {
        openCollection()
        val oldPage = CompletableDeferred<Result<VideoCollectionPage>>()
        coEvery { repository.getVideoCollectionItems("list", "", 30) } coAnswers { oldPage.await() }
        coEvery { repository.getVideoCollections() } returns Result.success(VideoCollectionCatalog())
        collections.loadMore(); runCurrent(); collections.refresh(silent = true); runCurrent()
        oldPage.complete(Result.success(collectionPage("old-tail"))); runCurrent()
        assertNull(collections.state.value.selectedId)
        assertTrue(collections.state.value.videos.isEmpty())
        assertNull(collections.state.value.nextOffset)
        assertFalse(collections.state.value.loadingVideos)
    }

    @Test fun aFailedCollectionDeletePreservesTheOpenCollection() = runTest(dispatcher) {
        openCollection()
        coEvery { repository.deleteVideoCollection("list") } returns Result.failure(Exception("offline"))
        collections.delete("list"); runCurrent()
        assertEquals("list", collections.state.value.selectedId)
        assertEquals(listOf("first"), collections.state.value.videos.map { it.videoId })
        assertEquals("offline", collections.state.value.error)
        assertFalse(collections.state.value.working)
    }

    @Test fun aFailedSearchReleasesLoadingAndCanBeRetried() = runTest(dispatcher) {
        openCollection()
        coEvery { repository.getVideoCollectionItems("list", "cats", 0) } returns Result.failure(Exception("offline"))
        collections.search("cats"); advanceTimeBy(250); runCurrent()
        assertFalse(collections.state.value.loadingVideos)
        assertEquals("offline", collections.state.value.error)
        coEvery { repository.getVideoCollectionItems("list", "cats", 0) } returns Result.success(collectionPage("cat"))
        collections.refresh(); runCurrent()
        assertEquals(listOf("cat"), collections.state.value.videos.map { it.videoId })
        assertNull(collections.state.value.error)
    }

    @Test fun failedHistoryReconciliationDoesNotUndoTheConfirmedDelete() = runTest(dispatcher) {
        coEvery { repository.getVideoHistory("") } returns Result.success(historyPage("removed", "kept"))
        coEvery { repository.deleteVideoHistory("removed") } returns Result.success(Unit)
        runCurrent(); history.refresh(); runCurrent()
        coEvery { repository.getVideoHistory("") } returns Result.failure(Exception("reload offline"))
        history.remove("removed"); runCurrent()
        assertEquals(listOf("kept"), history.state.value.items.map { it.videoId })
        assertFalse(history.state.value.loading)
        assertFalse(history.state.value.deleting)
        assertEquals("reload offline", history.state.value.error)
    }

    @Test fun failedWatchLaterReconciliationDoesNotUndoTheConfirmedRemoval() = runTest(dispatcher) {
        coEvery { repository.getVideoWatchLater("", "newest") } returns Result.success(laterPage("removed", "kept"))
        coEvery { repository.setVideoWatchLater("removed", false) } returns Result.success(Unit)
        runCurrent(); later.refresh(); runCurrent()
        coEvery { repository.getVideoWatchLater("", "newest") } returns Result.failure(Exception("reload offline"))
        later.remove("removed"); runCurrent()
        assertEquals(listOf("kept"), later.state.value.items.map { it.videoId })
        assertFalse(later.state.value.loading)
        assertEquals("reload offline", later.state.value.error)
    }

    @Test fun deletingACollectionDoesNotCloseAnotherCollectionOpenedDuringTheRequest() = runTest(dispatcher) {
        openCollection()
        val deletion = CompletableDeferred<Result<Unit>>()
        coEvery { repository.deleteVideoCollection("list") } coAnswers { deletion.await() }
        coEvery { repository.getVideoCollectionItems("other", "", 0) } returns Result.success(collectionPage("other-video"))
        coEvery { repository.getVideoCollections() } returns Result.success(
            VideoCollectionCatalog(listOf(VideoCollection(id = "other", name = "Other"))))
        collections.delete("list"); runCurrent()
        collections.select("other"); runCurrent()
        deletion.complete(Result.success(Unit)); runCurrent()
        assertEquals("other", collections.state.value.selectedId)
        assertEquals(listOf("other-video"), collections.state.value.videos.map { it.videoId })
        assertFalse(collections.state.value.working)
        assertFalse(collections.state.value.loadingVideos)
    }

    @Test fun failedCatalogReloadDoesNotRestoreTheDeletedCollectionOrItsSpinner() = runTest(dispatcher) {
        openCollection()
        coEvery { repository.deleteVideoCollection("list") } returns Result.success(Unit)
        coEvery { repository.getVideoCollections() } returns Result.failure(Exception("reload offline"))
        collections.delete("list"); runCurrent()
        assertTrue(collections.state.value.collections.isEmpty())
        assertNull(collections.state.value.selectedId)
        assertTrue(collections.state.value.videos.isEmpty())
        assertNull(collections.state.value.nextOffset)
        assertFalse(collections.state.value.loadingVideos)
        assertFalse(collections.state.value.working)
        assertEquals("reload offline", collections.state.value.error)
    }
}
