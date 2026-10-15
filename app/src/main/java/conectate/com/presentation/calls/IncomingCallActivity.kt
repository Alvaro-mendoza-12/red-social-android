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
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import conectate.com.data.service.CallCancellationGuard
import conectate.com.data.service.IncomingCallActionReceiver
import conectate.com.util.notificationAccountTargetFromIntent
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.theme.RedSocialTheme
import conectate.com.services.IncomingCallForegroundService
import conectate.com.util.TrustedNavigationIntents.markTrusted

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {

    private val answerViewModel: IncomingCallAnswerViewModel by viewModels()

    @Inject
    lateinit var themePreferences: ThemePreferences

    private var pendingAcceptInvite: CallInvite? = null
    private val terminalActionStarted = AtomicBoolean(false)
    private val activeInviteState = mutableStateOf<CallInvite?>(null)
    private val callerAvatarState = mutableStateOf<Bitmap?>(null)
    private val handlingState = mutableStateOf(false)
    private val answerErrorState = mutableStateOf<String?>(null)
    private val cancellationGuard by lazy { CallCancellationGuard(this) }
    private var cancellationObservation: Job? = null

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
            terminalActionStarted.set(false)
            handlingState.value = false
            answerErrorState.value = if (invite.isVideo) {
                    "Activa micrófono y cámara para contestar la videollamada."
                } else {
                    "Activa el micrófono para contestar la llamada."
                }
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
            configureLegacyLockScreenWindow()
        }

        val invite = intent.toInviteOrNull()
        if (invite == null) {
            finish()
            return
        }
        if (closeIfCancelled(invite)) return

        val autoAccept = intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)
        activeInviteState.value = invite
        if (savedInstanceState?.getString("pending_permission_call") == invite.callId) {
            pendingAcceptInvite = invite
            terminalActionStarted.set(true)
            handlingState.value = true
        }
        callerAvatarState.value = intent.toAvatarBitmapOrNull()
        observeCancellation(invite)

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
                val activeInvite by activeInviteState
                val callerAvatar by callerAvatarState
                activeInvite?.let { current ->
                    IncomingCallScreen(
                        invite = current,
                        callerAvatarBitmap = callerAvatar,
                        accountLabel = notificationAccountTargetFromIntent(intent)?.presentableLabel(),
                        onAccept = { acceptInvite(current) },
                        onDecline = { declineInvite(current) },
                        isHandling = handlingState.value,
                        errorMessage = answerErrorState.value,
                    )
                }
            }
        }

        lifecycleScope.launch {
            answerViewModel.state.collect { state ->
                val current = activeInviteState.value ?: return@collect
                if (!state.callId.equals(current.callId, ignoreCase = true)) return@collect
                when (state) {
                    is IncomingAnswerState.Preparing -> handlingState.value = true
                    is IncomingAnswerState.Ready -> {
                        if (!closeIfCancelled(current)) {
                            navigateToCall(current.callId)
                            finish()
                        }
                    }
                    is IncomingAnswerState.Failed -> {
                        terminalActionStarted.set(false)
                        handlingState.value = false
                        answerErrorState.value = state.message
                    }
                    IncomingAnswerState.Idle -> Unit
                }
            }
        }

        if (autoAccept && answerViewModel.state.value == IncomingAnswerState.Idle) {
            lifecycleScope.launch {
                acceptInvite(invite)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureLegacyLockScreenWindow() {
        // setShowWhenLocked/setTurnScreenOn existen desde API 27; la app aún
        // soporta API 24-26, donde estas banderas son la única alternativa.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val invite = intent.toInviteOrNull() ?: return
        if (closeIfCancelled(invite)) return
        val answerInProgress = isIncomingAnswerInProgress(answerViewModel.state.value, pendingAcceptInvite != null)
        if (answerInProgress && activeInviteState.value?.callId.equals(invite.callId, ignoreCase = true)) {
            if (intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)) acceptInvite(invite)
            return
        }
        if (answerInProgress) {
            IncomingCallActionReceiver.decline(this, invite, notificationAccountTargetFromIntent(intent))
            return
        }
        setIntent(intent)
        terminalActionStarted.set(false)
        handlingState.value = false
        answerErrorState.value = null
        pendingAcceptInvite = null
        activeInviteState.value = invite
        callerAvatarState.value = intent.toAvatarBitmapOrNull()
        observeCancellation(invite)
        if (intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)) {
            acceptInvite(invite)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingAcceptInvite?.callId?.let { outState.putString("pending_permission_call", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        activeInviteState.value?.let(::closeIfCancelled)
    }

    private fun observeCancellation(invite: CallInvite) {
        cancellationObservation?.cancel()
        // Keep listening while the screen is behind a permission dialog or another app.
        // Otherwise a cancellation could expire before the user returns to this screen.
        cancellationObservation = lifecycleScope.launch {
            cancellationGuard.observe(invite.callId).collect { cancelled ->
                if (cancelled) closeIfCancelled(invite)
            }
        }
    }

    private fun closeIfCancelled(invite: CallInvite): Boolean {
        if (!cancellationGuard.contains(invite.callId)) return false
        clearIncomingCallNotification(invite.callId)
        IncomingCallForegroundService.stop(this, invite.callId)
        val active = activeInviteState.value
        // A delayed intent for an older call must not dismiss a newer invitation.
        if (active == null || active.callId.equals(invite.callId, ignoreCase = true)) {
            pendingAcceptInvite = null
            terminalActionStarted.set(true)
            activeInviteState.value = null
            cancellationObservation?.cancel()
            finish()
        }
        return true
    }

    private fun acceptInvite(invite: CallInvite) {
        if (!activeInviteState.value?.callId.equals(invite.callId, ignoreCase = true)) return
        if (closeIfCancelled(invite)) return
        if (!terminalActionStarted.compareAndSet(false, true)) return
        handlingState.value = true
        answerErrorState.value = null
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
        if (!activeInviteState.value?.callId.equals(invite.callId, ignoreCase = true)) return
        if (closeIfCancelled(invite)) return
        clearIncomingCallNotification(invite.callId)
        IncomingCallForegroundService.stop(this, invite.callId)
        answerViewModel.answer(invite, notificationAccountTargetFromIntent(intent))
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
        if (!activeInviteState.value?.callId.equals(invite.callId, ignoreCase = true)) return
        if (closeIfCancelled(invite)) return
        if (!terminalActionStarted.compareAndSet(false, true)) return
        handlingState.value = true
        IncomingCallActionReceiver.decline(this, invite, notificationAccountTargetFromIntent(intent))
        finish()
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
    val isVideo = getBooleanExtra("call_is_video", false)

    if (callId.isBlank() || callerUserId.isBlank()) return null
    return CallInvite(
        callId = callId,
        title = title,
        callerUserId = callerUserId,
        callerName = callerName.ifBlank { "Usuario" },
        callerAvatarUrl = callerAvatarUrl,
        contextType = contextType,
        contextId = contextId,
        isVideo = isVideo,
    )
}

private fun Intent.toAvatarBitmapOrNull(): Bitmap? {
    val bytes = getByteArrayExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES) ?: return null
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
}

@Composable
internal fun IncomingCallScreen(
    invite: CallInvite,
    callerAvatarBitmap: Bitmap?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    accountLabel: String? = null,
    isHandling: Boolean = false,
    errorMessage: String? = null,
) {
    BackHandler(enabled = true) {
        // Require explicit action
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        val stackCallActions = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.5f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
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
                accountLabel?.let {
                    Text("Para: $it", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = if (isHandling) "Procesando llamada…"
                        else if (invite.isVideo) "Videollamada entrante" else "Llamada entrante",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
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

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = if (stackCallActions) 1 else 2
            ) {
                Button(
                    onClick = {
                        if (!isHandling) onDecline()
                    },
                    enabled = !isHandling,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("incoming_decline"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB3261E), contentColor = Color.White,
                        disabledContainerColor = Color(0xFF242424), disabledContentColor = Color(0xFFBDBDBD),
                    )
                ) {
                    androidx.compose.material3.Icon(Icons.Default.CallEnd, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Rechazar")
                }

                Button(
                    onClick = {
                        if (!isHandling) onAccept()
                    },
                    enabled = !isHandling,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("incoming_accept"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF236D36), contentColor = Color.White,
                        disabledContainerColor = Color(0xFF242424), disabledContentColor = Color(0xFFBDBDBD),
                    )
                ) {
                    androidx.compose.material3.Icon(Icons.Default.Call, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Contestar")
                }
            }
        }
    }
}








