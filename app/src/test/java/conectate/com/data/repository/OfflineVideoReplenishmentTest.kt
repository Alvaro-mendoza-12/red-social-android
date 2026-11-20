package conectate.com.data.repository

import androidx.work.ListenableWorker
import conectate.com.data.model.Video
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class OfflineVideoReplenishmentTest {
    @Test fun incompleteTransfersRetryWithinTheBudget() = runTest {
        val incomplete = OfflineVideoPrefetchResult(requested = 3, available = 1, downloaded = 1, failed = 2)
        assertEquals(ListenableWorker.Result.retry(), offlineVideoWorkResult("owner", 0) { incomplete })
        assertEquals(ListenableWorker.Result.failure(), offlineVideoWorkResult("owner", 4) { incomplete })
    }

    @Test fun completeOrExhaustedCatalogueCanFinish() = runTest {
        for (result in listOf(OfflineVideoPrefetchResult(1, 1, 1, 1), OfflineVideoPrefetchResult(3, 1, 0, 0))) {
            assertEquals(ListenableWorker.Result.success(), offlineVideoWorkResult("owner", 0) { result })
        }
    }

    @Test fun legacyWorkHasNoOwnerAndNeverOpensALibrary() = runTest {
        assertEquals(ListenableWorker.Result.success(), offlineVideoWorkResult(null, 0) { error("unexpected library access") })
    }

    @Test fun cancellationIsPropagatedAndOrdinaryFailuresUseBackoff() = runTest {
        val stopped = CancellationException("Worker stopped")
        try {
            offlineVideoWorkResult("owner", 0) { throw stopped }
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(stopped, actual)
        }
        assertEquals(ListenableWorker.Result.retry(), offlineVideoWorkResult("owner", 0) { error("offline") })
    }

    @Test fun queuedWorkForAnotherAccountSkipsTheFeed() = runTest {
        val guard = AuthSessionMutationGuard()
        val library = mockk<OfflineVideoRepository>()
        val videos = mockk<VideoRepository>()
        every { library.captureOwner() } returns guard.captureAccountLease { "current" }
        assertEquals(0, OfflineVideoPrefetchCoordinator(videos, library).replenish("previous").requested)
        verify(exactly = 0) { videos.getVideos(any(), any(), any(), any(), any()) }
    }

    @Test fun aChangedVisitDropsTheFetchedPageBeforeADownloadStarts() = runTest {
        val guard = AuthSessionMutationGuard()
        val owner = guard.captureAccountLease { "owner" }
        val library = mockk<OfflineVideoRepository>(relaxUnitFun = true)
        val videos = mockk<VideoRepository>()
        every { library.captureOwner() } returns owner
        every { library.configuredTargetCount(owner) } returns 1
        every { library.consumeFreshCycleRequest(owner) } returns false
        every { library.videos } returns MutableStateFlow(emptyList())
        every { videos.getVideos(offset = 0, limit = 60, random = true) } returns flow {
            guard.invalidate {}
            emit(listOf(Video(id = "fixture", videoUrl = "https://fixture.invalid/video")))
        }
        try {
            OfflineVideoPrefetchCoordinator(videos, library).replenish("owner")
            fail("expected stale work to end")
        } catch (_: CancellationException) {
            coVerify(exactly = 0) { library.download(any(), any()) }
        }
    }
}
