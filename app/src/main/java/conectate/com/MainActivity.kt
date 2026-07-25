package conectate.com

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import conectate.com.data.repository.AuthRepository
import conectate.com.data.model.LiveRole
import conectate.com.presentation.common.MainActivityContent
import conectate.com.presentation.common.StartupPrompts
import conectate.com.presentation.messages.isIncomingShareIntent
import conectate.com.presentation.messages.parseIncomingShareIntent
import conectate.com.presentation.messages.takeIncomingSharePersistablePermissions
import conectate.com.presentation.messages.toCapturedShareIntent
import conectate.com.presentation.theme.RedSocialTheme
import conectate.com.services.LiveChatHeadService
import conectate.com.util.IncomingSharePayload
import conectate.com.util.PendingOutgoingMediaStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val intentState = androidx.compose.runtime.mutableStateOf<Intent?>(null)
    private val incomingShareState = androidx.compose.runtime.mutableStateOf<IncomingSharePayload?>(null)
    private var incomingShareGeneration = 0L
    private var presenceHeartbeatJob: Job? = null
    private var presenceOfflineJob: Job? = null

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var notificationRepository: conectate.com.data.repository.NotificationRepository

    @Inject
    lateinit var themePreferences: conectate.com.data.local.prefs.ThemePreferences

    @Inject
    lateinit var voiceRoomRepository: conectate.com.data.repository.VoiceRoomRepository

    @Inject
    lateinit var callRepository: conectate.com.data.repository.CallRepository

    @Inject
    lateinit var liveRepository: conectate.com.data.repository.LiveRepository

    @Inject
    lateinit var userRepository: conectate.com.data.repository.UserRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        acceptIncomingIntent(intent)

        setContent {
            val isDarkMode by themePreferences.isDarkMode.collectAsStateWithLifecycle()
            val appTheme by themePreferences.appTheme.collectAsStateWithLifecycle()
            val fontOption by themePreferences.fontOption.collectAsStateWithLifecycle()
            val textScale by themePreferences.textScale.collectAsStateWithLifecycle()
            val currentIntent by intentState
            val incomingShare by incomingShareState
            RedSocialTheme(
                darkTheme = isDarkMode,
                appTheme = appTheme,
                fontOption = fontOption,
                textScale = textScale
            ) {
                StartupPrompts()
                MainActivityContent(
                    currentIntent = currentIntent,
                    onIntentConsumed = { intentState.value = null },
                    authRepository = authRepository,
                    notificationRepository = notificationRepository,
                    voiceRoomRepository = voiceRoomRepository,
                    callRepository = callRepository,
                    liveRepository = liveRepository,
                    activityScope = lifecycleScope
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptIncomingIntent(intent)
    }

    private fun acceptIncomingIntent(incomingIntent: Intent) {
        setIntent(incomingIntent)
        if (!isIncomingShareIntent(incomingIntent)) {
            intentState.value = incomingIntent
            return
        }

        val generation = ++incomingShareGeneration
        intentState.value = null
        lifecycleScope.launch {
            val parsed = parseIncomingShareIntent(this@MainActivity, incomingIntent)
            if (parsed == null) {
                if (generation == incomingShareGeneration) intentState.value = incomingIntent
                return@launch
            }

            takeIncomingSharePersistablePermissions(this@MainActivity, incomingIntent)
            val captured = PendingOutgoingMediaStore.persistIncomingShare(this@MainActivity, parsed)
                .getOrElse { error ->
                    parsed.copy(
                        items = emptyList(),
                        failedItemCount = parsed.failedItemCount + parsed.items.size,
                        captureError = error.message ?: "No se pudo conservar lo compartido"
                    )
                }

            if (generation != incomingShareGeneration) {
                PendingOutgoingMediaStore.deleteIncomingShare(this@MainActivity, captured)
                return@launch
            }

            incomingShareState.value
                ?.takeIf { it.id != captured.id }
                ?.let { PendingOutgoingMediaStore.deleteIncomingShare(this@MainActivity, it) }
            incomingShareState.value = captured
            val capturedIntent = captured.toCapturedShareIntent()
            setIntent(capturedIntent)
            intentState.value = capturedIntent
        }
    }

    private fun discardIncomingShare() {
        incomingShareGeneration += 1
        val payload = incomingShareState.value
        incomingShareState.value = null
        intentState.value = null
        setIntent(Intent(Intent.ACTION_MAIN).setClass(this, MainActivity::class.java))
        if (payload != null) {
            lifecycleScope.launch {
                PendingOutgoingMediaStore.deleteIncomingShare(this@MainActivity, payload)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncLiveChatHead(shouldShow = false)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        syncLiveChatHead(shouldShow = true)
    }

    override fun onStart() {
        super.onStart()
        syncLiveChatHead(shouldShow = false)
        cancelScheduledOfflineStatus()
        updateOnlineStatus(true)
        startPresenceHeartbeat()
    }

    override fun onStop() {
        stopPresenceHeartbeat()
        scheduleOfflineStatus()
        syncLiveChatHead(shouldShow = true)
        super.onStop()
    }

    private fun startPresenceHeartbeat() {
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = lifecycleScope.launch {
            while (isActive) {
                delay(25_000)
                updateOnlineStatus(true)
            }
        }
    }

    private fun stopPresenceHeartbeat() {
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = null
    }

    private fun scheduleOfflineStatus() {
        presenceOfflineJob?.cancel()
        presenceOfflineJob = lifecycleScope.launch {
            // Avoid false "últ. vez" while the app is changing activity, requesting projection,
            // or briefly bouncing between foreground/background.
            delay(20_000)
            updateOnlineStatus(false)
        }
    }

    private fun cancelScheduledOfflineStatus() {
        presenceOfflineJob?.cancel()
        presenceOfflineJob = null
    }

    private fun syncLiveChatHead(shouldShow: Boolean) {
        val session = liveRepository.activeSession.value
        val shouldRun = shouldShow && session?.role == LiveRole.HOST
        val intent = Intent(this, LiveChatHeadService::class.java).apply {
            action = if (shouldRun) LiveChatHeadService.ACTION_START else LiveChatHeadService.ACTION_STOP
        }
        if (shouldRun) {
            runCatching { startService(intent) }
                .onFailure {
                    android.util.Log.w("MainActivity", "LiveChatHeadService start skipped", it)
                }
        } else {
            runCatching { stopService(Intent(this, LiveChatHeadService::class.java)) }
                .onFailure {
                    android.util.Log.w("MainActivity", "LiveChatHeadService stop skipped", it)
                }
        }
    }

    private fun updateOnlineStatus(isOnline: Boolean) {
        lifecycleScope.launch {
            try {
                val userId = authRepository.getCurrentUserId()
                if (userId != null) {
                    userRepository.setOnlineStatus(userId, isOnline)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error updating online status", e)
            }
        }
    }
}


