package conectate.com.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.google.firebase.messaging.FirebaseMessaging
import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.model.Notification
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.NotificationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SessionSideEffects(
    isLoggedIn: Boolean,
    authRepository: AuthRepository,
    notificationRepository: NotificationRepository,
    onSuspensionDetected: (suspensionEndDate: String?, reason: String?) -> Unit,
    onForceLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) return@LaunchedEffect
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    scope.launch {
                        notificationRepository.registerFcmToken(token)
                    }
                }
            }
        }.onFailure { error ->
            android.util.Log.e("MainActivity", "Error in FCM setup", error)
        }
    }

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) return@LaunchedEffect
        notificationRepository.observeNotifications().collect { notification ->
            if (resolveNotificationType(notification) != "account_suspended") return@collect
            onSuspensionDetected(
                extractSuspensionEndDate(notification),
                extractSuspensionReason(notification)
            )
            runCatching { authRepository.signOut() }
            onForceLogout()
        }
    }

    LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) return@LaunchedEffect
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val gate = authRepository.enforceCurrentSessionIsActive()
            if (gate.isFailure) {
                val suspended = gate.exceptionOrNull() as? AccountSuspendedException
                onSuspensionDetected(
                    suspended?.suspensionEndDate,
                    suspended?.reason
                )
                runCatching { authRepository.signOut() }
                onForceLogout()
                break
            }
            delay(1000)
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
