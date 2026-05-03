package conectate.com.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.crash.CrashReporter
import conectate.com.core.logging.AppLogger
import conectate.com.core.observability.AppMetrics
import conectate.com.core.telemetry.AppTelemetry
import conectate.com.core.telemetry.TelemetryEvent
import conectate.com.data.repository.AdvancedMessagingRepository
import conectate.com.data.supabase.AuthService
import conectate.com.domain.sync.ChatSyncDataSource
import conectate.com.domain.sync.CommunitySyncDataSource
import conectate.com.domain.sync.GroupSyncDataSource
import conectate.com.util.NetworkConnectivityObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    private val authService: AuthService,
    private val advancedMessagingRepository: AdvancedMessagingRepository,
    private val networkObserver: NetworkConnectivityObserver,
    private val chatSyncDataSource: ChatSyncDataSource,
    private val groupSyncDataSource: GroupSyncDataSource,
    private val communitySyncDataSource: CommunitySyncDataSource,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger,
    private val crashReporter: CrashReporter,
    private val telemetry: AppTelemetry,
    private val appMetrics: AppMetrics,
    @ApplicationContext private val context: Context
) {

    private val scope = newSafeScope("SyncManager", dispatchers.io)
    private val syncMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val lastSyncTriggerAt = AtomicLong(0L)

    fun start() {
        if (!started.compareAndSet(false, true)) {
            logger.d(TAG, "start ignored: SyncManager already initialized")
            return }
        scheduleWorker()
        observeNetwork()
        observeSession()
    }

    fun triggerSync(reason: String = "manual") {
        val now = System.currentTimeMillis()
        val last = lastSyncTriggerAt.get()
        var normalizedReason = reason.trim().lowercase()
        val bypassThrottle =
            normalizedReason == "network_available" ||
                normalizedReason.contains("reconnect") ||
                normalizedReason.endsWith("_retry") ||
                normalizedReason == "ui_retry"
        if (!bypassThrottle && now - last < MIN_TRIGGER_INTERVAL_MS) {
            logger.d(TAG, "Skipping in-process sync request came too soon ($reason)")
            return }

        if (!networkObserver.isNetworkAvailable()) {
            logger.d(TAG, "Skipping sync trigger no internet ($reason)")
            return }

        var userId = authService.getCurrentUserId()
        if (userId.isNullOrBlank()) {
            logger.d(TAG, "Skipping sync trigger no authenticated user ($reason)")
            return
        }

        lastSyncTriggerAt.set(now)
        scheduleImmediateWorker(reason)

        scope.launch {
            runCatching { syncNow(reason) }
                .onFailure { error ->
                    logger.e(TAG, "triggerSync failed ($reason)", error)
                    crashReporter.recordException(
                        throwable = error,
                        extra = mapOf("source" to "triggerSync", "reason" to reason)
                    )
                }
        }
    }

    fun scheduleWorker() {
        var constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        var request = PeriodicWorkRequestBuilder<SyncWorker>(
            RECURRING_SYNC_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    suspend fun syncNow(reason: String = "manual") {
        if (!networkObserver.isNetworkAvailable()) {
            logger.d(TAG, "syncNow skipped: no internet ($reason)")
            appMetrics.recordFailure(
                metric = "sync_cycle",
                attributes = mapOf("reason" to "network_unavailable")
            )
            return
        }

        var userId = authService.getCurrentUserId()
        if (userId == null) {
            logger.d(TAG, "syncNow skipped: no authenticated user ($reason)")
            return
        }

        syncMutex.withLock {
            val startedAt = System.currentTimeMillis()
            telemetry.track(
                TelemetryEvent(
                    name = "sync_started",
                    attributes = mapOf("reason" to reason, "user_id" to userId)
                )
            )

            logger.i(TAG, "Sync started (reason=$reason)")

            val failures = mutableListOf<String>()

            var retriedMessages = chatSyncDataSource.retryPendingMessages().getOrElse { error ->
                failures += "retryPendingMessages:${error.message.orEmpty()}"
                logger.w(TAG, "retryPendingMessages failed", error)
                0
            }

            var retriedDeletes = chatSyncDataSource.retryPendingDeletes().getOrElse { error ->
                failures += "retryPendingDeletes:${error.message.orEmpty()}"
                logger.w(TAG, "retryPendingDeletes failed", error)
                0
            }

            val processedScheduled = advancedMessagingRepository.processDueScheduledJobs().getOrElse { error ->
                failures += "processDueScheduledJobs:${error.message.orEmpty()}"
                logger.w(TAG, "processDueScheduledJobs failed", error)
                0
            }

            val processedAutoDestruct = advancedMessagingRepository.processDueAutoDestructTasks().getOrElse { error ->
                failures += "processDueAutoDestructTasks:${error.message.orEmpty()}"
                logger.w(TAG, "processDueAutoDestructTasks failed", error)
                0
            }

            if (chatSyncDataSource.syncChats().isFailure) {
                failures += "syncChats"
            }
            if (groupSyncDataSource.syncGroups().isFailure) {
                failures += "syncGroups"
            }
            if (communitySyncDataSource.syncCommunities().isFailure) {
                failures += "syncCommunities"
            }

            var durationMs = System.currentTimeMillis() - startedAt
            val success = failures.isEmpty()

            telemetry.track(
                TelemetryEvent(
                    name = "sync_finished",
                    attributes = mapOf(
                        "reason" to reason,
                        "duration_ms" to durationMs.toString(),
                        "success" to success.toString(),
                        "retried_messages" to retriedMessages.toString(),
                        "retried_deletes" to retriedDeletes.toString(),
                        "scheduled_sent" to processedScheduled.toString(),
                        "autodestruct_processed" to processedAutoDestruct.toString(),
                        "failures" to failures.joinToString("|")
                    )
                )
            )

            if (failures.isNotEmpty()) {
                logger.w(TAG, "Sync finished with partial failures: ${failures.joinToString()}")
                appMetrics.recordFailure(
                    metric = "sync_cycle",
                    attributes = mapOf(
                        "reason" to reason,
                        "failure_count" to failures.size.toString()
                    )
                )
                if (failures.size >= STAGE_FAILURE_THRESHOLD_FOR_THROW) {
                    val error = IllegalStateException("Critical sync failure: ${failures.joinToString()}")
                    crashReporter.recordException(
                        throwable = error,
                        extra = mapOf("reason" to reason, "duration_ms" to durationMs.toString())
                    )
                    throw error
                }
            } else {
                logger.i(
                    TAG,
                    "Sync completed in ${durationMs}ms (retriedMessages=$retriedMessages, retriedDeletes=$retriedDeletes)"
                )
                appMetrics.incrementCounter(
                    metric = "sync_cycle_success",
                    attributes = mapOf("reason" to reason)
                )
                appMetrics.recordLatency(
                    metric = "sync_cycle",
                    durationMs = durationMs,
                    attributes = mapOf("reason" to reason)
                )
            }
        }
    }

    private fun observeNetwork() {
        scope.launch {
            networkObserver.isConnected.collectLatest { connected ->
                telemetry.track(
                    TelemetryEvent(
                        name = "network_changed",
                        attributes = mapOf("connected" to connected.toString())
                    )
                )

                if (connected) {
                    // The observer already waits for a validated connection, so keep this short.
                    delay(150)
                    triggerSync(reason = "network_available")
                }
            }
        }
    }

    private fun observeSession() {
        scope.launch {
            authService.observeSession().collectLatest { session ->
                var userId = session?.user?.id?.trim()?.lowercase()
                crashReporter.setUserId(userId)

                if (!userId.isNullOrBlank()) {
                    triggerSync(reason = "session_authenticated")
                }
            }
        }
    }

    private fun scheduleImmediateWorker(reason: String) {
        var constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        var request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SYNC_REASON_KEY to reason))
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                20,
                TimeUnit.SECONDS
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val PERIODIC_SYNC_WORK_NAME = "conectate_periodic_sync"
        private const val IMMEDIATE_SYNC_WORK_NAME = "conectate_immediate_sync"
        private const val RECURRING_SYNC_INTERVAL_MINUTES = 15L
        private const val MIN_TRIGGER_INTERVAL_MS = 10_000L
        private const val STAGE_FAILURE_THRESHOLD_FOR_THROW = 3

        const val SYNC_REASON_KEY = "sync_reason"
    }
}



