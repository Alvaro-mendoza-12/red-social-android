package conectate.com.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessaging
import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.model.Notification
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.NotificationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val SESSION_GATE_INTERVAL_MS = 180_000L


fun SessionSideEffects(
    isLoggedIn: Boolean,
    authRepository: AuthRepository,
    notificationRepository: NotificationRepository,
    onSuspensionDetected: (suspensionEndDate: String?, reason: String?) -> Unit,
    onForceLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val processLifecycle = remember { ProcessLifecycleOwner.get().lifecycle }
    val latestOnSuspensionDetected by rememberUpdatedState(onSuspensionDetected)
    val latestOnForceLogout by rememberUpdatedState(onForceLogout)
    var isAppInForeground by remember {
        mutableStateOf(processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var hasHandledForcedExit by remember { mutableStateOf(false) }

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

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            hasHandledForcedExit = false
            return
        }
    }

    suspend fun handleForcedSessionExit(
        shouldReportSuspension: Boolean,
        suspensionEndDate: String? = null,
        reason: String? = null
    ) {
        if (hasHandledForcedExit) return
        hasHandledForcedExit = true
        if (shouldReportSuspension) {
            latestOnSuspensionDetected(suspensionEndDate, reason)
        }
        runCatching { authRepository.forceLocalLogout() }
            .onFailure {
                android.util.Log.w("MainActivity", "Local forced logout failed", it)
            }
        latestOnForceLogout()
    }

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result?.trim().orEmpty()
                    if (token.isBlank()) return
                    scope.launch {
                        notificationRepository.registerFcmToken(token)
                            .onFailure {
                                android.util.Log.w("MainActivity", "FCM token registration failed", it)
                            }
                    }
                } else {
                    android.util.Log.w("MainActivity", "FCM token task was not successful", task.exception)
                }
            }
        }.onFailure { error ->
            android.util.Log.e("MainActivity", "Error in FCM setup", error)
        }
    }

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) return
        notificationRepository.observeNotifications()
            .catch { error ->
                android.util.Log.e("MainActivity", "Notification stream failed", error)
            }
            .collect { notification ->
                if (resolveNotificationType(notification) != "account_suspended") return
                handleForcedSessionExit(
                    shouldReportSuspension = true,
                    suspensionEndDate = extractSuspensionEndDate(notification),
                    reason = extractSuspensionReason(notification)
                )
            }
    }

    LaunchedEffect(isLoggedIn, isAppInForeground) {
        if (!isLoggedIn || !isAppInForeground) return
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

private fun resolveNotificationType(notification: Notification): String {
    val subtype = notification.extraData
        ?.get("notification_type")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.lowercase()
    if (!subtype.isNullOrBlank()) return subtype
    val baseType = notification.type.trim().lowercase()
    if (baseType != "message") return baseType
    val lowerContent = notification.content?.trim()?.lowercase().orEmpty()
    return when {
        lowerContent.contains("cuenta fue suspendida") ||
            lowerContent.contains("cuenta ha sido suspendida") -> "account_suspended"
        lowerContent.contains("cuenta ha sido habilitada") ||
            lowerContent.contains("cuenta fue habilitada") ||
            lowerContent.contains("cuenta habilitada nuevamente") -> "account_enabled"
        else -> baseType
    }
}

private fun extractSuspensionEndDate(notification: Notification): String? {
    return notification.extraData
        ?.get("suspended_until")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun extractSuspensionReason(notification: Notification): String? {
    val fromExtra = notification.extraData
        ?.get("suspension_reason")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (!fromExtra.isNullOrBlank()) return fromExtra
    return notification.content
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}






