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
import conectate.com.presentation.theme.RedSocialTheme
import conectate.com.services.LiveChatHeadService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val intentState = androidx.compose.runtime.mutableStateOf<Intent?>(null)
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
        intentState.value = intent

        setContent {
            val isDarkMode by themePreferences.isDarkMode.collectAsStateWithLifecycle()
            val fontOption by themePreferences.fontOption.collectAsStateWithLifecycle()
            val textScale by themePreferences.textScale.collectAsStateWithLifecycle()
            val currentIntent by intentState
            RedSocialTheme(
                darkTheme = isDarkMode,
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
        setIntent(intent)
        intentState.value = intent
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


