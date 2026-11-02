package conectate.com.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import conectate.com.data.sync.PushRegistrationScheduler
import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.model.Notification
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.NotificationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import conectate.com.util.declaredNotificationType as resolveNotificationType
import conectate.com.util.notificationExtra

private const val SESSION_GATE_INTERVAL_MS = 180_000L


@Composable
fun SessionSideEffects(
    isLoggedIn: Boolean,
    activeUserId: String?,
    authRepository: AuthRepository,
    notificationRepository: NotificationRepository,
    onSuspensionDetected: (suspensionEndDate: String?, reason: String?) -> Unit,
    onForceLogout: () -> Unit
) {
    val applicationContext = LocalContext.current.applicationContext
    val processLifecycle = remember { ProcessLifecycleOwner.get().lifecycle }
    val latestOnSuspensionDetected by rememberUpdatedState(onSuspensionDetected)
    val latestOnForceLogout by rememberUpdatedState(onForceLogout)
    var isAppInForeground by remember {
        mutableStateOf(processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val forcedExit = remember(activeUserId) { ForcedSessionExitGate(activeUserId, authRepository::getCurrentUserId) }

    DisposableEffect(processLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> isAppInForeground = true
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_PAUSE -> isAppInForeground = false
                else -> Unit
            }
        }
        processLifecycle.addObserver(observer)
        onDispose { processLifecycle.removeObserver(observer) }
    }

    suspend fun handleForcedSessionExit(
        shouldReportSuspension: Boolean,
        suspensionEndDate: String? = null,
        reason: String? = null,
        recipientId: String? = activeUserId,
    ) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!forcedExit.accept(recipientId)) return
        if (shouldReportSuspension) {
            latestOnSuspensionDetected(suspensionEndDate, reason)
        }
        runCatching { authRepository.forceLocalLogout() }
            .onFailure {
                android.util.Log.w("MainActivity", "Local forced logout failed", it)
            }
        latestOnForceLogout()
    }

    LaunchedEffect(isLoggedIn, activeUserId, isAppInForeground) {
        if (isLoggedIn && !activeUserId.isNullOrBlank() && isAppInForeground) {
            PushRegistrationScheduler.enqueue(applicationContext)
        }
    }

    LaunchedEffect(isLoggedIn, activeUserId) {
        if (!isLoggedIn || activeUserId.isNullOrBlank()) return@LaunchedEffect
        notificationRepository.observeNotifications()
            .catch { error ->
                android.util.Log.e("MainActivity", "Notification stream failed", error)
            }
            .collect { notification ->
                if (resolveNotificationType(notification) != "account_suspended") return@collect
                handleForcedSessionExit(
                    shouldReportSuspension = true,
                    suspensionEndDate = extractSuspensionEndDate(notification),
                    reason = extractSuspensionReason(notification),
                    recipientId = notification.recipientId,
                )
            }
    }

    LaunchedEffect(isLoggedIn, activeUserId, isAppInForeground) {
        if (!isLoggedIn || activeUserId.isNullOrBlank() || !isAppInForeground) return@LaunchedEffect
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val gate = authRepository.enforceCurrentSessionIsActive()
            if (gate.isFailure) {
                val suspended = gate.exceptionOrNull() as? AccountSuspendedException
                handleForcedSessionExit(
                    shouldReportSuspension = suspended != null,
                    suspensionEndDate = suspended?.suspensionEndDate,
                    reason = suspended?.reason
                )
                break
            }
            delay(SESSION_GATE_INTERVAL_MS)
        }
    }
}

private fun extractSuspensionEndDate(notification: Notification): String? =
    notificationExtra(notification, "suspended_until")

private fun extractSuspensionReason(notification: Notification): String? =
    notificationExtra(notification, "suspension_reason")
        ?: notification.content?.trim()?.takeIf(String::isNotBlank)
