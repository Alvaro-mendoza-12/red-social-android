package conectate.com.presentation.navigation

import android.os.Bundle
import androidx.navigation.NavController
import java.util.UUID

/** Ordinary cold starts keep their Messages policy; unfinished sign-in keeps its destination. */
internal object AuthenticationJourneyRestoration {
    private const val PROCESS = "novvin_process_instance"
    private const val PENDING = "novvin_pending_authentication_journey"
    val processInstanceId: String = UUID.randomUUID().toString()

    fun stateForCreate(state: Bundle?, currentProcessId: String = processInstanceId): Bundle? =
        state?.takeIf { it.getString(PROCESS) == currentProcessId || it.getBoolean(PENDING, false) }

    fun save(state: Bundle, pending: Boolean, currentProcessId: String = processInstanceId) {
        state.putString(PROCESS, currentProcessId)
        state.putBoolean(PENDING, pending)
    }

    fun wasPending(state: Bundle?): Boolean = state?.getBoolean(PENDING, false) == true

    /** Null means the restored graph has not selected a destination yet. */
    fun pending(navController: NavController): Boolean? = when (navController.currentDestination?.route) {
        null, Screen.Splash.route -> null
        Screen.Login.route, Screen.Register.route, Screen.AddAccountLogin.route,
        Screen.AccountRecoveryHelp.route, Screen.NotificationAccountBridge.route -> true
        Screen.PrivacyPolicy.route -> try {
            navController.getBackStackEntry(Screen.Register.route)
            true
        } catch (_: IllegalArgumentException) { false }
        else -> false
    }
}
