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
import conectate.com.presentation.navigation.AuthenticationJourneyRestoration
import conectate.com.presentation.messages.isIncomingShareIntent
import conectate.com.presentation.messages.shouldAcceptRestoredShare
import conectate.com.presentation.messages.parseIncomingShareIntent
import conectate.com.presentation.messages.takeIncomingSharePersistablePermissions
import conectate.com.presentation.messages.toCapturedShareIntent
import conectate.com.presentation.messages.IncomingShareDelivery
import conectate.com.presentation.messages.IncomingShareDeliveryGate
import conectate.com.presentation.messages.IncomingShareCleanupQueue
import conectate.com.presentation.messages.restoredShareRequestId
import conectate.com.presentation.messages.capturedIncomingShareId
import conectate.com.presentation.messages.ShareDismissal
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
        private const val SHARE_CONSUMED_KEY = "novvin_incoming_share_consumed"
        private const val NAVIGATION_INTENT_ID_KEY = "novvin_navigation_intent_id"
        private const val SHARE_REQUEST_ID_KEY = "novvin_incoming_share_request_id"
        private const val SHARE_PAYLOAD_ID_KEY = "novvin_incoming_share_payload_id"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(conectate.com.data.local.prefs.AppLocalePreferences.wrap(newBase))
    }

    private val navigationIntentState = androidx.compose.runtime.mutableStateOf(java.util.UUID.randomUUID().toString())
    private val intentState = androidx.compose.runtime.mutableStateOf<Intent?>(null)
    private val incomingShareState = androidx.compose.runtime.mutableStateOf<IncomingShareDelivery<IncomingSharePayload>?>(null)
    private val incomingShareDeliveries = IncomingShareDeliveryGate<IncomingSharePayload>()
    private val incomingShareCleanup = IncomingShareCleanupQueue<IncomingSharePayload> { it.id }
    private var incomingCaptureJob: Job? = null
    private var pendingShareIntent: Intent? = null
    private var presenceHeartbeatJob: Job? = null
    private var presenceOfflineJob: Job? = null
    private var authenticationJourneyPending = false

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
        // Keep unfinished authentication and its original content after process death.
        // Ordinary cold starts still use Messages; rotation keeps the existing stack.
        val restoredState = AuthenticationJourneyRestoration.stateForCreate(savedInstanceState)
        authenticationJourneyPending = AuthenticationJourneyRestoration.wasPending(restoredState)
        super.onCreate(restoredState)
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "StartupTiming",
                "MainActivity super.onCreate completed in ${android.os.SystemClock.elapsedRealtime() - createStartedAtMs} ms"
            )
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val restoredIntent = intent
        val replayedShare = isIncomingShareIntent(restoredIntent) && !shouldAcceptRestoredShare(
            fromHistory = restoredIntent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
            alreadyConsumed = savedInstanceState?.getBoolean(SHARE_CONSUMED_KEY, false) == true,
            knownPendingCapture = restoredShareRequestId(capturedIncomingShareId(restoredIntent).orEmpty(),
                savedInstanceState?.getString(SHARE_PAYLOAD_ID_KEY), savedInstanceState?.getString(SHARE_REQUEST_ID_KEY)) != null
        )
        acceptIncomingIntent(if (replayedShare) {
            Intent(Intent.ACTION_MAIN).setClass(this, MainActivity::class.java)
        } else restoredIntent, savedInstanceState?.getString(SHARE_PAYLOAD_ID_KEY), savedInstanceState?.getString(SHARE_REQUEST_ID_KEY),
            restoredState?.getString(NAVIGATION_INTENT_ID_KEY))

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
            val enterToSend by messagesPreferences.enterToSendEnabled.collectAsStateWithLifecycle()
            val showTypingIndicator by messagesPreferences.typingIndicatorsEnabled.collectAsStateWithLifecycle()
            val navigationIntentId by navigationIntentState
            val currentIntent by intentState
            val incomingDelivery by incomingShareState
            val incomingShare = incomingDelivery?.payload
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
                    conectate.com.presentation.messages.LocalChatBehavior provides
                        conectate.com.presentation.messages.ChatBehaviorSettings(enterToSend, showTypingIndicator),
                ) {
                    MainActivityContent(
                        currentIntent = currentIntent,
                        navigationIntentId = navigationIntentId,
                        onIntentConsumed = { consumeIncomingIntent(currentIntent) },
                        incomingShare = incomingShare,
                        incomingShareRequestId = incomingDelivery?.requestId,
                        onIncomingShareConsumed = { discardIncomingShare(incomingDelivery) },
                        authRepository = authRepository,
                        notificationRepositoryProvider = notificationRepository,
                        voiceRoomRepositoryProvider = voiceRoomRepository,
                        callRepositoryProvider = callRepository,
                        liveRepositoryProvider = liveRepository,
                        activityScope = lifecycleScope,
                        onAuthenticationFlowChanged = { authenticationJourneyPending = it },
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
        AuthenticationJourneyRestoration.save(outState, authenticationJourneyPending)
        outState.putString(NAVIGATION_INTENT_ID_KEY, navigationIntentState.value)
        outState.putString(SHARE_REQUEST_ID_KEY, incomingShareState.value?.requestId)
        outState.putString(SHARE_PAYLOAD_ID_KEY, incomingShareState.value?.payload?.id)
        outState.putBoolean(SHARE_CONSUMED_KEY, !isIncomingShareIntent(intent) && incomingShareState.value == null)
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
        if (intent === consumedIntent && consumedIntent !== pendingShareIntent) {
            setIntent(Intent(Intent.ACTION_MAIN).setClass(this, MainActivity::class.java))
        }
    }

    private fun acceptIncomingIntent(incomingIntent: Intent, savedPayloadId: String? = null, savedRequestId: String? = null,
        restoredNavigationId: String? = null) {
        navigationIntentState.value = restoredNavigationId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        // A new link/notification also supersedes an unfinished file capture.
        // Otherwise that old capture can replace the new destination when it finishes.
        val generation = incomingShareDeliveries.beginIntent()
        // Pin the new capture before cancelling the old one: it may read that old capture's files.
        val capture = if (isIncomingShareIntent(incomingIntent)) incomingShareCleanup.beginCapture() else null
        incomingCaptureJob?.cancel()
        incomingCaptureJob = null
        setIntent(incomingIntent)
        if (!isIncomingShareIntent(incomingIntent)) {
            intentState.value = incomingIntent
            return
        }

        intentState.value = null
        val captureJob = lifecycleScope.launch {
            val parsed = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val trustedCaptureId = capturedIncomingShareId(incomingIntent)?.takeIf {
                        restoredShareRequestId(it, savedPayloadId, savedRequestId) != null
                    }
                    parseIncomingShareIntent(this@MainActivity, incomingIntent, trustedCaptureId)?.also {
                        takeIncomingSharePersistablePermissions(this@MainActivity, incomingIntent)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (incomingShareDeliveries.isCurrentCapture(generation)) {
                    android.util.Log.w("MainActivity", "Cannot read incoming share", error)
                }
                null
            }
            if (parsed == null) {
                if (incomingShareDeliveries.isCurrentCapture(generation)) {
                    // Never send an unreadable parcel on to navigation to parse it again.
                    setIntent(pendingShareIntent ?: Intent(Intent.ACTION_MAIN).setClass(this@MainActivity, MainActivity::class.java))
                    intentState.value = null
                    conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo preparar lo compartido. Vuelve a adjuntarlo.")
                }
                return@launch
            }

            val captured = PendingOutgoingMediaStore.persistIncomingShare(this@MainActivity, parsed)
                .getOrElse { error ->
                    parsed.copy(
                        items = emptyList(),
                        failedItemCount = parsed.failedItemCount + parsed.items.size,
                        captureError = error.message ?: "No se pudo conservar lo compartido"
                    )
                }

            val delivery = incomingShareDeliveries.publish(generation, captured,
                restoredShareRequestId(captured.id, savedPayloadId, savedRequestId) ?: java.util.UUID.randomUUID().toString())
            if (delivery == null) {
                cleanupIncomingShare(captured)
                return@launch
            }

            val previous = incomingShareState.value?.payload?.takeIf { it.id != captured.id }
            incomingShareState.value = delivery
            val capturedIntent = captured.toCapturedShareIntent()
            pendingShareIntent = capturedIntent
            setIntent(capturedIntent)
            intentState.value = capturedIntent
            // Publish without a suspending cleanup between the generation check and UI delivery.
            if (previous != null) cleanupIncomingShare(previous)
        }
        incomingCaptureJob = captureJob
        captureJob.invokeOnCompletion {
            runOnUiThread {
                if (incomingCaptureJob === captureJob) incomingCaptureJob = null
                cleanReadyIncomingShares(incomingShareCleanup.finishCapture(checkNotNull(capture), incomingShareState.value?.payload?.id))
            }
        }
    }

    private fun cleanupIncomingShare(payload: IncomingSharePayload) {
        cleanReadyIncomingShares(incomingShareCleanup.enqueue(payload, incomingShareState.value?.payload?.id))
    }

    private fun cleanReadyIncomingShares(payloads: List<IncomingSharePayload>) {
        if (payloads.isEmpty()) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            payloads.forEach { PendingOutgoingMediaStore.deleteIncomingShare(this@MainActivity, it) }
        }
    }

    private fun discardIncomingShare(expected: IncomingShareDelivery<IncomingSharePayload>?): ShareDismissal {
        if (isDestroyed) return ShareDismissal.IGNORED
        val consumed = incomingShareDeliveries.consume(expected) ?: return ShareDismissal.IGNORED
        val payload = consumed.delivery.payload
        incomingShareState.value = null
        pendingShareIntent = null
        if (consumed.dismissal == ShareDismissal.CURRENT) {
            intentState.value = null
            setIntent(Intent(Intent.ACTION_MAIN).setClass(this, MainActivity::class.java))
        }
        cleanupIncomingShare(payload)
        return consumed.dismissal
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


