package conectate.com.data.sync

import android.content.Context
import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.core.crash.CrashReporter
import conectate.com.core.logging.AppLogger
import conectate.com.core.observability.AppMetrics
import conectate.com.core.telemetry.AppTelemetry
import conectate.com.data.repository.AdvancedMessagingRepository
import conectate.com.data.supabase.AuthService
import conectate.com.domain.sync.ChatSyncDataSource
import conectate.com.domain.sync.CommunitySyncDataSource
import conectate.com.domain.sync.GroupSyncDataSource
import conectate.com.util.NetworkConnectivityObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class SyncManagerOutboxFastPathTest {
    private val authService = mockk<AuthService>()
    private val advancedMessagingRepository = mockk<AdvancedMessagingRepository>()
    private val networkObserver = mockk<NetworkConnectivityObserver>()
    private val chatSyncDataSource = mockk<ChatSyncDataSource>()
    private val groupSyncDataSource = mockk<GroupSyncDataSource>()
    private val communitySyncDataSource = mockk<CommunitySyncDataSource>()
    private val logger = mockk<AppLogger>(relaxed = true)
    private val crashReporter = mockk<CrashReporter>(relaxed = true)
    private val telemetry = mockk<AppTelemetry>(relaxed = true)
    private val appMetrics = mockk<AppMetrics>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        every { networkObserver.isNetworkAvailable() } returns true
        every { authService.getCurrentUserId() } returns "user-1"
        coEvery { chatSyncDataSource.retryPendingMessages() } returns Result.success(1)
        coEvery { chatSyncDataSource.retryPendingDeletes() } returns Result.success(0)
        coEvery { advancedMessagingRepository.processDueScheduledJobs() } returns Result.success(0)
        coEvery { advancedMessagingRepository.processDueAutoDestructTasks() } returns Result.success(0)
        coEvery { chatSyncDataSource.syncChats() } returns Result.success(Unit)
        coEvery { groupSyncDataSource.syncGroups() } returns Result.success(Unit)
        coEvery { communitySyncDataSource.syncCommunities() } returns Result.success(Unit)

        syncManager = SyncManager(
            authService = authService,
            advancedMessagingRepository = advancedMessagingRepository,
            networkObserver = networkObserver,
            chatSyncDataSource = chatSyncDataSource,
            groupSyncDataSource = groupSyncDataSource,
            communitySyncDataSource = communitySyncDataSource,
            dispatchers = dispatchers,
            logger = logger,
            crashReporter = crashReporter,
            telemetry = telemetry,
            appMetrics = appMetrics,
            context = context,
        )
    }

    @Test
    fun outboxWakeupRetriesPendingWorkWithoutTriggeringFullCatalogSyncs() = runBlocking {
        syncManager.syncNow("chat_message_pending")

        coVerify(exactly = 1) { chatSyncDataSource.retryPendingMessages() }
        coVerify(exactly = 1) { chatSyncDataSource.retryPendingDeletes() }
        coVerify(exactly = 0) { chatSyncDataSource.syncChats() }
        coVerify(exactly = 0) { groupSyncDataSource.syncGroups() }
        coVerify(exactly = 0) { communitySyncDataSource.syncCommunities() }
    }

    @Test
    fun manualSyncStillRefreshesChatsGroupsAndCommunities() = runBlocking {
        syncManager.syncNow("manual")

        coVerify(exactly = 1) { chatSyncDataSource.retryPendingMessages() }
        coVerify(exactly = 1) { chatSyncDataSource.syncChats() }
        coVerify(exactly = 1) { groupSyncDataSource.syncGroups() }
        coVerify(exactly = 1) { communitySyncDataSource.syncCommunities() }
    }
}
