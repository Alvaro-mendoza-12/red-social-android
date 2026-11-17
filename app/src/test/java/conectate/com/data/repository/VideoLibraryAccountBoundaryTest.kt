package conectate.com.data.repository

import android.content.Context
import conectate.com.core.moderation.ContentModerationService
import conectate.com.core.observability.AppMetrics
import conectate.com.data.cache.UpstashCacheService
import conectate.com.data.local.dao.VideoCommentDao
import conectate.com.data.local.dao.VideoStatsDao
import conectate.com.data.localfallback.*
import conectate.com.data.supabase.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real repository calls: only credential renewal and the server are controlled. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class VideoLibraryAccountBoundaryTest(private val operation: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun operations() = listOf(
            "watch-read", "watch-write", "history-read", "history-progress", "history-delete",
            "collections-read", "collection-save", "collection-delete", "collection-videos",
            "collection-add", "collection-move",
        )
    }
    private val guard = AuthSessionMutationGuard()
    private var account = "a"
    private val auth = mockk<AuthService>()
    private val api = mockk<LocalFallbackApiClient>()
    private val sent = mutableListOf<String>()
    private var responseGate: CompletableDeferred<Unit>? = null
    private val repository: VideoRepository

    init {
        every { auth.getCurrentUserId() } answers { account }
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        every { auth.currentSession } returns MutableStateFlow<AuthResponse?>(null)
        val realtime = mockk<UbuntuRealtimeClient>(relaxed = true)
        every { realtime.events } returns emptyFlow()
        val config = mockk<SupabaseConfig>()
        every { config.isLocalFallbackPrimary } returns true
        every { api.isConfigured } returns true
        coEvery { auth.getAccessTokenForAccountAwait(any()) } coAnswers { auth.getAccessTokenAwait() }
        coEvery { api.getVideoWatchLater(any(), any(), any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(VideoWatchLaterPage()) }
        coEvery { api.setVideoWatchLater(any(), any(), any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(Unit) }
        coEvery { api.getVideoHistory(any(), any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(VideoHistoryPage()) }
        coEvery { api.recordVideoHistory(any(), any(), any(), any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(Unit) }
        coEvery { api.deleteVideoHistory(any(), any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(Unit) }
        coEvery { api.getVideoCollections(any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(VideoCollectionCatalog()) }
        coEvery { api.saveVideoCollection(any(), any(), any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(VideoCollection(id = "collection")) }
        coEvery { api.deleteVideoCollection(any(), any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(Unit) }
        coEvery { api.getVideoCollectionItems(any(), any(), any(), any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(VideoCollectionPage()) }
        coEvery { api.setVideoCollectionItem(any(), any(), any(), any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(Unit) }
        coEvery { api.moveVideoCollectionItem(any(), any(), any(), any()) } coAnswers { sent += firstArg<String>(); responseGate?.await(); Result.success(Unit) }
        repository = VideoRepository(config, auth, mockk<StorageService>(), mockk<VideoStatsDao>(),
            mockk<VideoCommentDao>(), mockk<NotificationRepository>(), mockk<UserRepository>(),
            mockk<HashtagRepository>(), mockk<UpstashCacheService>(), mockk<AppMetrics>(),
            mockk<ContentModerationService>(), api, realtime, mockk<Context>())
    }

    @After fun closeBackgroundObservers() {
        // The production singleton owns these observers; a short-lived test fixture must release them.
        val field = VideoRepository::class.java.getDeclaredField("repositoryScope")
        field.isAccessible = true
        (field.get(repository) as CoroutineScope).cancel()
    }

    private suspend fun call() {
        when (operation) {
            "watch-read" -> repository.getVideoWatchLater().getOrThrow()
            "watch-write" -> repository.setVideoWatchLater("video", false).getOrThrow()
            "history-read" -> repository.getVideoHistory().getOrThrow()
            "history-progress" -> repository.recordVideoHistory("video", 2000, 4000).getOrThrow()
            "history-delete" -> repository.deleteVideoHistory(null).getOrThrow()
            "collections-read" -> repository.getVideoCollections().getOrThrow()
            "collection-save" -> repository.saveVideoCollection("name").getOrThrow()
            "collection-delete" -> repository.deleteVideoCollection("collection").getOrThrow()
            "collection-videos" -> repository.getVideoCollectionItems("collection").getOrThrow()
            "collection-add" -> repository.setVideoCollectionItem("collection", "video", true).getOrThrow()
            "collection-move" -> repository.moveVideoCollectionItem("collection", "video", "up").getOrThrow()
            else -> error(operation)
        }
    }

    private fun switchTo(next: String) { guard.invalidate { account = next } }

    @Test fun changedAccountDuringCredentialRenewalNeverSends() = runTest {
        val token = CompletableDeferred<String?>()
        coEvery { auth.getAccessTokenAwait() } coAnswers { token.await() }
        val task = async { call() }
        runCurrent(); switchTo("b"); token.complete("token-b")
        try { task.await() } catch (_: CancellationException) { }
        assertEquals("A command captured for A must not use B's token", emptyList<String>(), sent)
    }

    @Test fun returningToTheSameAccountDoesNotRestoreTheOldAuthorization() = runTest {
        val token = CompletableDeferred<String?>()
        coEvery { auth.getAccessTokenAwait() } coAnswers { token.await() }
        val task = async { call() }
        runCurrent(); switchTo("b"); switchTo("a"); token.complete("token-a-new-visit")
        try { task.await() } catch (_: CancellationException) { }
        assertTrue("A new sign-in must not authorize the old command", sent.isEmpty())
    }

    @Test fun currentAccountStillReachesTheServerWithItsCredential() = runTest {
        coEvery { auth.getAccessTokenAwait() } returns "token-a"
        call()
        assertEquals(listOf("token-a"), sent)
    }

    @Test fun responseFromThePreviousVisitCannotReturnToTheNewAccount() = runTest {
        coEvery { auth.getAccessTokenAwait() } returns "token-a"
        responseGate = CompletableDeferred()
        val task = async { call(); "visible response" }
        runCurrent()
        assertEquals(listOf("token-a"), sent)
        switchTo("b"); responseGate!!.complete(Unit)
        try { task.await(); fail("The old response must not reach the new visit") }
        catch (_: CancellationException) { }
    }
}
