package conectate.com

import android.content.Intent
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import conectate.com.data.local.prefs.AppFontOption
import conectate.com.data.repository.AuthRepository
import conectate.com.data.model.LiveRole
import conectate.com.presentation.common.MainActivityContent
import conectate.com.presentation.common.StartupPrompts
import conectate.com.presentation.auth.SplashScreen
import conectate.com.presentation.theme.RedSocialTheme
import conectate.com.services.LiveChatHeadService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val intentState = androidx.compose.runtime.mutableStateOf<Intent?>(null)
    private var presenceHeartbeatJob: Job? = null
    private var presenceOfflineJob: Job? = null
    @Volatile
    private var dependencies: MainActivityDependencies? = null

    private data class MainActivityDependencies(
        val authRepository: AuthRepository,
        val notificationRepository: conectate.com.data.repository.NotificationRepository,
        val appSettingsPreferences: conectate.com.data.local.prefs.AppSettingsPreferences,
        val themePreferences: conectate.com.data.local.prefs.ThemePreferences,
        val voiceRoomRepository: conectate.com.data.repository.VoiceRoomRepository,
        val callRepository: conectate.com.data.repository.CallRepository,
        val liveRepository: conectate.com.data.repository.LiveRepository,
        val userRepository: conectate.com.data.repository.UserRepository
    )

    @Inject
    lateinit var authRepositoryProvider: Provider<AuthRepository>

    @Inject
    lateinit var notificationRepositoryProvider: Provider<conectate.com.data.repository.NotificationRepository>

    @Inject
    lateinit var appSettingsPreferencesProvider: Provider<conectate.com.data.local.prefs.AppSettingsPreferences>

    @Inject
    lateinit var themePreferencesProvider: Provider<conectate.com.data.local.prefs.ThemePreferences>

    @Inject
    lateinit var voiceRoomRepositoryProvider: Provider<conectate.com.data.repository.VoiceRoomRepository>

    @Inject
    lateinit var callRepositoryProvider: Provider<conectate.com.data.repository.CallRepository>

    @Inject
    lateinit var liveRepositoryProvider: Provider<conectate.com.data.repository.LiveRepository>

    @Inject
    lateinit var userRepositoryProvider: Provider<conectate.com.data.repository.UserRepository>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate: drawing lightweight startup frame")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        intentState.value = intent

        setContent {
            var loadedDependencies by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(dependencies)
            }
            val isDarkMode by (
                loadedDependencies?.themePreferences?.isDarkMode ?: flowOf(false)
            ).collectAsStateWithLifecycle(initialValue = false)
            val fontOption by (
                loadedDependencies?.themePreferences?.fontOption ?: flowOf(AppFontOption.DEFAULT)
            ).collectAsStateWithLifecycle(initialValue = AppFontOption.DEFAULT)
            val textScale by (
                loadedDependencies?.themePreferences?.textScale ?: flowOf(1f)
            ).collectAsStateWithLifecycle(initialValue = 1f)
            val reduceMotionEnabled by (
                loadedDependencies?.appSettingsPreferences?.reduceMotionEnabled ?: flowOf(false)
            ).collectAsStateWithLifecycle(initialValue = false)
            val currentIntent by intentState

            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (loadedDependencies == null) {
                    loadedDependencies = loadMainDependencies()
                    dependencies = loadedDependencies
                    onDependenciesReady()
                }
            }

            RedSocialTheme(
                darkTheme = isDarkMode,
                fontOption = fontOption,
                textScale = textScale,
                reduceMotion = reduceMotionEnabled
            ) {
                val deps = loadedDependencies
                if (deps == null) {
                    SplashScreen()
                } else {
                    StartupPrompts()
                    MainActivityContent(
                        currentIntent = currentIntent,
                        onIntentConsumed = { intentState.value = null },
                        authRepository = deps.authRepository,
                        notificationRepository = deps.notificationRepository,
                        voiceRoomRepository = deps.voiceRoomRepository,
                        callRepository = deps.callRepository,
                        liveRepository = deps.liveRepository,
                        activityScope = lifecycleScope
                    )
                }
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
        dependencies?.let {
            syncLiveChatHead(shouldShow = false)
            cancelScheduledOfflineStatus()
            updateOnlineStatus(true)
            startPresenceHeartbeat()
        }
    }

    override fun onStop() {
        stopPresenceHeartbeat()
        scheduleOfflineStatus()
        syncLiveChatHead(shouldShow = true)
        super.onStop()
    }

    private fun startPresenceHeartbeat() {
        if (dependencies == null) return
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
        val deps = dependencies ?: return
        val session = deps.liveRepository.activeSession.value
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
        val deps = dependencies ?: return
        lifecycleScope.launch {
            try {
                val userId = deps.authRepository.getCurrentUserId()
                if (userId != null) {
                    deps.userRepository.setOnlineStatus(userId, isOnline)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error updating online status", e)
            }
        }
    }

    private suspend fun loadMainDependencies(): MainActivityDependencies = withContext(Dispatchers.Default) {
        Log.d("MainActivity", "Loading main dependencies after first frame")
        MainActivityDependencies(
            authRepository = authRepositoryProvider.get(),
            notificationRepository = notificationRepositoryProvider.get(),
            appSettingsPreferences = appSettingsPreferencesProvider.get(),
            themePreferences = themePreferencesProvider.get(),
            voiceRoomRepository = voiceRoomRepositoryProvider.get(),
            callRepository = callRepositoryProvider.get(),
            liveRepository = liveRepositoryProvider.get(),
            userRepository = userRepositoryProvider.get()
        ).also {
            Log.d("MainActivity", "Main dependencies loaded")
        }
    }

    private fun onDependenciesReady() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        syncLiveChatHead(shouldShow = false)
        cancelScheduledOfflineStatus()
        updateOnlineStatus(true)
        startPresenceHeartbeat()
    }
}


