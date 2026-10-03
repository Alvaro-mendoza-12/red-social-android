package conectate.com.presentation.calls

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import conectate.com.data.model.CallInvite
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.CallRepository
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.navigation.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CallInvitationOverlayHost(
    isLoggedIn: Boolean,
    navController: NavHostController,
    authRepository: AuthRepository,
    callRepository: CallRepository
) {
    if (!isLoggedIn) return

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val invite = callRepository.incomingInvite.collectAsStateWithLifecycle().value
    val currentUserId = authRepository.getCurrentUserId()
    var pendingCallId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var acceptingCallId by remember { mutableStateOf<String?>(null) }

    fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun accept(incoming: CallInvite, accountId: String?) {
        if (acceptingCallId != null || accountId.isNullOrBlank() ||
            !accountId.equals(authRepository.getCurrentUserId(), ignoreCase = true) ||
            !incoming.callId.equals(callRepository.incomingInvite.value?.callId, ignoreCase = true)) return
        acceptingCallId = incoming.callId
        scope.launch {
            try {
                val result = callRepository.acceptIncomingCall(incoming)
                if (!accountId.equals(authRepository.getCurrentUserId(), ignoreCase = true)) return@launch
                if (result.isSuccess && callRepository.getCurrentCallId().equals(incoming.callId, ignoreCase = true)) {
                    navController.navigate(Screen.Call.createRoute(incoming.callId)) { launchSingleTop = true }
                } else if (result.isFailure) {
                    AppFeedbackBus.showError("No se pudo contestar la llamada. Intenta nuevamente.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppFeedbackBus.showError("No se pudo contestar la llamada. Intenta nuevamente.")
            } finally { acceptingCallId = null }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val requestedCallId = pendingCallId
        val requestedAccountId = pendingAccountId
        pendingCallId = null
        pendingAccountId = null
        val latestInvite = callRepository.incomingInvite.value
        val latestAccountId = authRepository.getCurrentUserId()
        val hasAudio = hasPermission(Manifest.permission.RECORD_AUDIO)
        val hasCamera = hasPermission(Manifest.permission.CAMERA)
        if (canAnswerAfterPermissions(requestedCallId, requestedAccountId, latestInvite, latestAccountId, hasAudio, hasCamera)) {
            accept(requireNotNull(latestInvite), latestAccountId)
        } else if (latestInvite?.callId.equals(requestedCallId, ignoreCase = true) &&
            !requestedAccountId.isNullOrBlank() && requestedAccountId.equals(latestAccountId, ignoreCase = true)) {
            AppFeedbackBus.showError(
                if (latestInvite?.isVideo == true) "Activa micrófono y cámara para contestar la videollamada."
                else "Activa el micrófono para contestar la llamada.",
            )
        }
    }

    LaunchedEffect(currentUserId) {
        currentUserId?.let { uid ->
            callRepository.ensureSubscribedToCallUserChannel(uid)
        }
    }

    CallInvitationOverlay(
        invite = invite,
        isHandling = pendingCallId != null || acceptingCallId != null,
        onAccept = { incoming ->
            if (pendingCallId == null && acceptingCallId == null) {
                val missing = buildList {
                    if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                    if (incoming.isVideo && !hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
                }
                if (missing.isEmpty()) accept(incoming, currentUserId)
                else {
                    pendingCallId = incoming.callId
                    pendingAccountId = currentUserId
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }
        },
        onDecline = { incoming ->
            scope.launch {
                callRepository.declineIncomingCall(incoming)
            }
        }
    )
}








