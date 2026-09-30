package conectate.com

import android.content.Intent
import android.content.Context
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
import conectate.com.presentation.messages.isIncomingShareIntent
import conectate.com.presentation.messages.parseIncomingShareIntent
import conectate.com.presentation.messages.takeIncomingSharePersistablePermissions
import conectate.com.presentation.messages.toCapturedShareIntent
import conectate.com.presentation.theme.RedSocialTheme
import conectate.com.services.LiveChatHeadService
import conectate.com.util.IncomingSharePayload
import conectate.com.util.PendingOutgoingMediaStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val PROCESS_INSTANCE_KEY = "novvin_process_instance"
        private val processInstanceId = java.util.UUID.randomUUID().toString()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(conectate.com.data.local.prefs.AppLocalePreferences.wrap(newBase))
    }

    private val intentState = androidx.compose.runtime.mutableStateOf<Intent?>(null)
    private val incomingShareState = androidx.compose.runtime.mutableStateOf<IncomingSharePayload?>(null)
    private var incomingShareGeneration = 0L
    private var presenceHeartbeatJob: Job? = null
    private var presenceOfflineJob: Job? = null

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var notificationRepository: Lazy<conectate.com.data.repository.NotificationRepository>

    @Inject
    lateinit var themePreferences: conectate.com.data.local.prefs.ThemePreferences

    @Inject
    lateinit var voiceRoomRepository: Lazy<conectate.com.data.repository.VoiceRoomRepository>

    @Inject
    lateinit var callRepository: Lazy<conectate.com.data.repository.CallRepository>

    @Inject
    lateinit var liveRepository: Lazy<conectate.com.data.repository.LiveRepository>

    @Inject
    lateinit var userRepository: Lazy<conectate.com.data.repository.UserRepository>

    @Inject
    lateinit var moderationNoticeCenter: conectate.com.core.moderation.ModerationNoticeCenter

    @Inject
    lateinit var contentSafetyPreferences: conectate.com.data.local.prefs.ContentSafetyPreferences

    @Inject
    lateinit var messagesPreferences: conectate.com.data.local.prefs.MessagesPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        val createStartedAtMs = android.os.SystemClock.elapsedRealtime()
        // A process restored after the app was closed must start at Messages, not
        // restore an old tab from Navigation's saved back stack. Rotation in the
        // same process still keeps its state.
        super.onCreate(savedInstanceState?.takeIf {
            it.getString(PROCESS_INSTANCE_KEY) == processInstanceId
        })
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "StartupTiming",
                "MainActivity super.onCreate completed in ${android.os.SystemClock.elapsedRealtime() - createStartedAtMs} ms"
            )
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        acceptIncomingIntent(intent)

        lifecycleScope.launch {
            moderationNoticeCenter.notices.collect { notice ->
                if (contentSafetyPreferences.moderationWarningsEnabled.value) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        notice,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        setContent {
            val isDarkMode by themePreferences.isDarkMode.collectAsStateWithLifecycle()
            val appTheme by themePreferences.appTheme.collectAsStateWithLifecycle()
            val fontOption by themePreferences.fontOption.collectAsStateWithLifecycle()
            val textScale by themePreferences.textScale.collectAsStateWithLifecycle()
            val sensitiveContentMode by contentSafetyPreferences.sensitiveContentMode.collectAsStateWithLifecycle()
            val chatAccent by messagesPreferences.chatAccent.collectAsStateWithLifecycle()
            val chatBackground by messagesPreferences.chatBackground.collectAsStateWithLifecycle()
            val currentIntent by intentState
            val incomingShare by incomingShareState
            RedSocialTheme(
                darkTheme = isDarkMode,
                appTheme = appTheme,
                fontOption = fontOption,
                textScale = textScale
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    conectate.com.presentation.theme.LocalSensitiveContentMode provides sensitiveContentMode,
                    conectate.com.presentation.theme.LocalChatAccentKey provides chatAccent,
                    conectate.com.presentation.theme.LocalChatBackgroundKey provides chatBackground,
                ) {
                    MainActivityContent(
                        currentIntent = currentIntent,
                        onIntentConsumed = { consumeIncomingIntent(currentIntent) },
                        incomingShare = incomingShare,
                        onIncomingShareConsumed = ::discardIncomingShare,
                        authRepository = authRepository,
                        notificationRepositoryProvider = notificationRepository,
                        voiceRoomRepositoryProvider = voiceRoomRepository,
                        callRepositoryProvider = callRepository,
                        liveRepositoryProvider = liveRepository,
                        activityScope = lifecycleScope
                    )
                }
            }
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "StartupTiming",
                "MainActivity setContent submitted in ${android.os.SystemClock.elapsedRealtime() - createStartedAtMs} ms"
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PROCESS_INSTANCE_KEY, processInstanceId)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptIncomingIntent(intent)
    }

    private fun consumeIncomingIntent(consumedIntent: Intent?) {
        // A delayed navigation callback must not consume a newer delivery.
        if (consumedIntent == null || intentState.value !== consumedIntent) return
        intentState.value = null
        // Android keeps Activity.intent through recreation. Clearing only the
        // Compose state would replay a consumed link on rotation or font changes.
        // Captured shares retain their intent until discardIncomingShare handles it.
        if (incomingShareState.value == null && intent === consumedIntent) {
            setIntent(Intent(Intent.ACTION_MAIN).setClass(this, MainActivity::class.java))
        }
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
        if (!shouldShow) {
            // Foreground entry only removes the bubble. Resolving the LIVE
            // repository here used to construct its graph on every cold start.
            runCatching { stopService(Intent(this, LiveChatHeadService::class.java)) }
                .onFailure { android.util.Log.w("MainActivity", "LiveChatHeadService stop skipped", it) }
            return
        }
        val hasSession = authRepository.getAuthStateFlow().value?.user?.id?.isNotBlank() == true
        val session = if (hasSession) liveRepository.get().activeSession.value else null
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
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val userId = authRepository.getCurrentUserId()
                if (userId != null) {
                    userRepository.get().setOnlineStatus(userId, isOnline)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error updating online status", e)
            }
        }
    }
}


