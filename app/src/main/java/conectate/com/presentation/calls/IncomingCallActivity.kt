package conectate.com.presentation.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import conectate.com.MainActivity
import conectate.com.data.local.prefs.AppSettingsPreferences
import conectate.com.data.local.prefs.ThemePreferences
import conectate.com.data.model.CallInvite
import conectate.com.data.repository.CallRepository
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.theme.RedSocialTheme
import conectate.com.services.IncomingCallForegroundService

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {

    @Inject
    lateinit var callRepository: CallRepository

    @Inject
    lateinit var themePreferences: ThemePreferences

    @Inject
    lateinit var appSettingsPreferences: AppSettingsPreferences

    private var pendingAcceptInvite: CallInvite? = null
    private var permissionStatusMessage by mutableStateOf<String?>(null)

    private val microphonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val invite = pendingAcceptInvite
            pendingAcceptInvite = null
            if (granted) {
                permissionStatusMessage = null
                if (invite != null) {
                    acceptInviteAfterPermission(invite)
                }
            } else {
                permissionStatusMessage = MICROPHONE_PERMISSION_DENIED_MESSAGE
                Toast.makeText(this, MICROPHONE_PERMISSION_DENIED_MESSAGE, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Show on lock screen + turn screen on (WhatsApp-like)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        var invite = intent.toInviteOrNull()
        if (invite == null) {
            finish()
            return
        }

        val autoAccept = intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)
        var avatarBitmap = intent.toAvatarBitmapOrNull()

        setContent {
            val isDarkMode by themePreferences.isDarkMode.collectAsStateWithLifecycle()
            val fontOption by themePreferences.fontOption.collectAsStateWithLifecycle()
            val textScale by themePreferences.textScale.collectAsStateWithLifecycle()
            val reduceMotionEnabled by appSettingsPreferences.reduceMotionEnabled.collectAsStateWithLifecycle()
            RedSocialTheme(
                darkTheme = isDarkMode,
                fontOption = fontOption,
                textScale = textScale,
                reduceMotion = reduceMotionEnabled
            ) {
                IncomingCallScreen(
                    invite = invite,
                    callerAvatarBitmap = avatarBitmap,
                    permissionStatusMessage = permissionStatusMessage,
                    onAccept = { acceptInvite(invite) },
                    onDecline = { declineInvite(invite) }
                )
            }
        }

        if (savedInstanceState == null && autoAccept) {
            lifecycleScope.launch {
                acceptInvite(invite)
            }
        }
    }

    private fun acceptInvite(invite: CallInvite) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAcceptInvite = invite
            permissionStatusMessage = "Necesitamos permiso de microfono para responder la llamada."
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        permissionStatusMessage = null
        acceptInviteAfterPermission(invite)
    }

    private fun acceptInviteAfterPermission(invite: CallInvite) {
        lifecycleScope.launch {
            clearIncomingCallNotification(invite.callId)
            IncomingCallForegroundService.stop(this@IncomingCallActivity)
            val acceptResult = callRepository.acceptIncomingCall(invite)
            if (acceptResult.isFailure) {
                val error = acceptResult.exceptionOrNull()
                val failureMessage = error?.message ?: "No se pudo aceptar la llamada. Intenta de nuevo."
                Log.e(TAG, "Failed to accept incoming call ${invite.callId}", error)
                permissionStatusMessage = failureMessage
                Toast.makeText(
                    this@IncomingCallActivity,
                    failureMessage,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            callRepository.refreshAudioSession()
            delay(300)
            callRepository.refreshAudioSessionRepeatedly(attempts = 2, delayMs = 500)
            navigateToCall(invite.callId)
            finish()
        }
    }

    private fun declineInvite(invite: CallInvite) {
        lifecycleScope.launch {
            clearIncomingCallNotification(invite.callId)
            IncomingCallForegroundService.stop(this@IncomingCallActivity)
            val declineResult = callRepository.declineIncomingCall(invite)
            declineResult.exceptionOrNull()?.let { error ->
                Log.e(TAG, "Failed to decline incoming call ${invite.callId}", error)
                Toast.makeText(
                    this@IncomingCallActivity,
                    error.message ?: "No se pudo rechazar la llamada correctamente.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            finish()
        }
    }

    private fun navigateToCall(callId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "call_return")
            putExtra("call_id", callId)
        }.also { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startActivity(intent)
    }

    private fun clearIncomingCallNotification(callId: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(callId.hashCode())
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear incoming call notification for $callId", error)
        }
    }

    companion object {
        private const val TAG = "IncomingCallActivity"
        const val EXTRA_AUTO_ACCEPT = "extra_auto_accept"
        const val EXTRA_CALLER_AVATAR_BYTES = "extra_caller_avatar_bytes"
        private const val MICROPHONE_PERMISSION_DENIED_MESSAGE =
            "Debes permitir el microfono para responder la llamada."
    }
}

private fun Intent.toInviteOrNull(): CallInvite? {
    return buildIncomingCallInvite(
        callId = getStringExtra("call_id"),
        callerUserId = getStringExtra("caller_user_id"),
        callerName = getStringExtra("caller_name"),
        callerAvatarUrl = getStringExtra("caller_avatar_url"),
        title = getStringExtra("call_title"),
        contextType = getStringExtra("call_context_type"),
        contextId = getStringExtra("call_context_id"),
        isVideo = getBooleanExtra("call_is_video", false)
    )
}

internal fun buildIncomingCallInvite(
    callId: String?,
    callerUserId: String?,
    callerName: String?,
    callerAvatarUrl: String?,
    title: String?,
    contextType: String?,
    contextId: String?,
    isVideo: Boolean = false
): CallInvite? {
    val normalizedCallId = callId?.trim().orEmpty()
    val normalizedCallerUserId = callerUserId?.trim().orEmpty()
    val normalizedCallerName = callerName?.trim().orEmpty().ifBlank { "Usuario" }
    val normalizedCallerAvatarUrl = callerAvatarUrl?.trim().orEmpty().ifBlank { null }
    val normalizedTitle = title?.trim().orEmpty().ifBlank { normalizedCallerName }
    val normalizedContextType = contextType?.trim().orEmpty().ifBlank { "dm" }
    val normalizedContextId = contextId?.trim().orEmpty().ifBlank { null }

    if (normalizedCallId.isBlank() || normalizedCallerUserId.isBlank()) return null
    return CallInvite(
        callId = normalizedCallId,
        title = normalizedTitle,
        callerUserId = normalizedCallerUserId,
        callerName = normalizedCallerName,
        callerAvatarUrl = normalizedCallerAvatarUrl,
        contextType = normalizedContextType,
        contextId = normalizedContextId,
        isVideo = isVideo
    )
}

private fun Intent.toAvatarBitmapOrNull(): Bitmap? {
    val bytes = getByteArrayExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES) ?: return null
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
}

@Composable
private fun IncomingCallScreen(
    invite: CallInvite,
    callerAvatarBitmap: Bitmap?,
    permissionStatusMessage: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    BackHandler(enabled = true) {
        // Require explicit action
    }

    var isHandling by remember { mutableStateOf(false) }
    var scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 24.dp, vertical = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = invite.callerName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (invite.isVideo) "Videollamada entrante" else "Llamada entrante",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                if (invite.title.isNotBlank() && !invite.title.equals(invite.callerName, ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = invite.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                if (!permissionStatusMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = permissionStatusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB4AB)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1E1E)),
                contentAlignment = Alignment.Center
            ) {
                if (callerAvatarBitmap != null) {
                    Image(
                        bitmap = callerAvatarBitmap.asImageBitmap(),
                        contentDescription = "Foto del llamante",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                } else {
                    CircularProfileImage(imageUrl = invite.callerAvatarUrl, size = 180.dp, showBorder = false)
                }
            }

            Row(
                modifier = Modifier.padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (isHandling) return@Button
                        isHandling = true
                        scope.launch {
                            onDecline()
                            isHandling = false
                        }
                    },
                    enabled = !isHandling,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    androidx.compose.material3.Icon(Icons.Default.CallEnd, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Rechazar")
                }

                Spacer(modifier = Modifier.width(20.dp))

                Button(
                    onClick = {
                        if (isHandling) return@Button
                        isHandling = true
                        scope.launch {
                            onAccept()
                            isHandling = false
                        }
                    },
                    enabled = !isHandling,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = if (invite.isVideo) Icons.Default.Videocam else Icons.Default.Call,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Aceptar")
                }
            }
        }
    }
}








