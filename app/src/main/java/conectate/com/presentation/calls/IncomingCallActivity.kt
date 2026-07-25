package conectate.com.presentation.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
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
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import conectate.com.MainActivity
import conectate.com.data.local.prefs.ThemePreferences
import conectate.com.data.model.CallInvite
import conectate.com.data.repository.CallRepository
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.common.toUserFacingMessage
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.theme.RedSocialTheme
import conectate.com.services.IncomingCallForegroundService
import conectate.com.util.TrustedNavigationIntents.markTrusted

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

    private var pendingAcceptInvite: CallInvite? = null

    private val acceptPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val invite = pendingAcceptInvite
        pendingAcceptInvite = null
        if (invite == null) return@registerForActivityResult

        val hasAudio = grants[Manifest.permission.RECORD_AUDIO] == true ||
            hasPermission(Manifest.permission.RECORD_AUDIO)
        val hasCamera = !invite.isVideo ||
            grants[Manifest.permission.CAMERA] == true ||
            hasPermission(Manifest.permission.CAMERA)

        if (hasAudio && hasCamera) {
            performAcceptInvite(invite)
        } else {
            AppFeedbackBus.showError(
                if (invite.isVideo) {
                    "Activa micrófono y cámara para contestar la videollamada."
                } else {
                    "Activa el micrófono para contestar la llamada."
                }
            )
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
            val appTheme by themePreferences.appTheme.collectAsStateWithLifecycle()
            val fontOption by themePreferences.fontOption.collectAsStateWithLifecycle()
            val textScale by themePreferences.textScale.collectAsStateWithLifecycle()
            RedSocialTheme(
                darkTheme = isDarkMode,
                appTheme = appTheme,
                fontOption = fontOption,
                textScale = textScale
            ) {
                IncomingCallScreen(
                    invite = invite,
                    callerAvatarBitmap = avatarBitmap,
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
        val missingPermissions = requiredCallPermissions(invite)
            .filterNot(::hasPermission)
            .toTypedArray()
        if (missingPermissions.isNotEmpty()) {
            pendingAcceptInvite = invite
            acceptPermissionsLauncher.launch(missingPermissions)
            return
        }
        performAcceptInvite(invite)
    }

    private fun performAcceptInvite(invite: CallInvite) {
        lifecycleScope.launch {
            clearIncomingCallNotification(invite.callId)
            IncomingCallForegroundService.stop(this@IncomingCallActivity)
            val result = callRepository.acceptIncomingCall(invite)
            if (result.isFailure) {
                AppFeedbackBus.showError(
                    result.exceptionOrNull()?.toUserFacingMessage(
                        "No se pudo conectar la llamada en este momento."
                    ) ?: "No se pudo conectar la llamada en este momento."
                )
                finish()
                return@launch
            }
            callRepository.refreshAudioSession()
            delay(300)
            callRepository.refreshAudioSessionRepeatedly(attempts = 2, delayMs = 500)
            navigateToCall(invite.callId)
            finish()
        }
    }

    private fun requiredCallPermissions(invite: CallInvite): List<String> {
        return buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (invite.isVideo) add(Manifest.permission.CAMERA)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun declineInvite(invite: CallInvite) {
        lifecycleScope.launch {
            clearIncomingCallNotification(invite.callId)
            IncomingCallForegroundService.stop(this@IncomingCallActivity)
            callRepository.declineIncomingCall(invite)
            finish()
        }
    }

    private fun navigateToCall(callId: String) {
        val navigationIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "call_return")
            putExtra("call_id", callId)
        }.also { markTrusted(this, it) }
        startActivity(navigationIntent)
    }

    private fun clearIncomingCallNotification(callId: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(callId.hashCode())
        }
    }

    companion object {
        const val EXTRA_AUTO_ACCEPT = "extra_auto_accept"
        const val EXTRA_CALLER_AVATAR_BYTES = "extra_caller_avatar_bytes"
    }
}

private fun Intent.toInviteOrNull(): CallInvite? {
    var callId = getStringExtra("call_id")?.trim().orEmpty()
    var callerUserId = getStringExtra("caller_user_id")?.trim().orEmpty()
    var callerName = getStringExtra("caller_name")?.trim().orEmpty()
    var callerAvatarUrl = getStringExtra("caller_avatar_url")?.trim().orEmpty().ifBlank { null }
    var title = getStringExtra("call_title")?.trim().orEmpty().ifBlank { callerName }
    var contextType = getStringExtra("call_context_type")?.trim().orEmpty().ifBlank { "dm" }
    var contextId = getStringExtra("call_context_id")?.trim().orEmpty().ifBlank { null }

    if (callId.isBlank() || callerUserId.isBlank()) return null
    return CallInvite(
        callId = callId,
        title = title,
        callerUserId = callerUserId,
        callerName = callerName.ifBlank { "Usuario" },
        callerAvatarUrl = callerAvatarUrl,
        contextType = contextType,
        contextId = contextId
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
                    text = "Llamada entrante",
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
                    androidx.compose.material3.Icon(Icons.Default.Call, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Aceptar")
                }
            }
        }
    }
}








