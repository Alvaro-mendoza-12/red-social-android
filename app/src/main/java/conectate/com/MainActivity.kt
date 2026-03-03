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
import conectate.com.presentation.common.MainActivityContent
import conectate.com.presentation.common.StartupPrompts
import conectate.com.presentation.theme.RedSocialTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val intentState = androidx.compose.runtime.mutableStateOf<Intent?>(null)

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var notificationRepository: conectate.com.data.repository.NotificationRepository

    @Inject
    lateinit var themePreferences: conectate.com.data.local.prefs.ThemePreferences

    @Inject
    lateinit var voiceRoomRepository: conectate.com.data.repository.VoiceRoomRepository

    @Inject
    lateinit var userRepository: conectate.com.data.repository.UserRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        intentState.value = intent

        setContent {
            val isDarkMode by themePreferences.isDarkMode.collectAsStateWithLifecycle()
            val currentIntent by intentState
            RedSocialTheme(darkTheme = isDarkMode) {
                StartupPrompts()
                MainActivityContent(
                    currentIntent = currentIntent,
                    onIntentConsumed = { intentState.value = null },
                    authRepository = authRepository,
                    notificationRepository = notificationRepository,
                    voiceRoomRepository = voiceRoomRepository,
                    activityScope = lifecycleScope
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState.value = intent
    }

    override fun onResume() {
        super.onResume()
        updateOnlineStatus(true)
    }

    override fun onPause() {
        super.onPause()
        updateOnlineStatus(false)
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
