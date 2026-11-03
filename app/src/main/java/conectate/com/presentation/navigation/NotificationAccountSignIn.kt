package conectate.com.presentation.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/** Return only from the login entry opened by this specific notification bridge. */
internal object NotificationAccountSignIn {
    private const val BRIDGE_ENTRY = "notification_sign_in_bridge_entry"
    private const val SIGNED_IN_USER = "notification_signed_in_user"
    private const val ORIGIN_PROCESS = "notification_sign_in_origin_process"
    private const val ORIGIN_SESSION_STAMP = "notification_sign_in_origin_session_stamp"

    fun open(navController: NavController, bridge: NavBackStackEntry, sessionStamp: String? = null) {
        navController.navigate(Screen.AddAccountLogin.route) { launchSingleTop = true }
        navController.currentBackStackEntry?.savedStateHandle?.set(BRIDGE_ENTRY, bridge.id)
        navController.currentBackStackEntry?.savedStateHandle?.set(ORIGIN_PROCESS, AuthenticationJourneyRestoration.processInstanceId)
        navController.currentBackStackEntry?.savedStateHandle?.set(ORIGIN_SESSION_STAMP, sessionStamp)
    }

    /** Only a login restored in another process may reuse an already confirmed active session. */
    fun restoredBridge(
        navController: NavController,
        activeEntryId: String,
        currentProcessId: String = AuthenticationJourneyRestoration.processInstanceId,
    ): NavBackStackEntry? {
        val current = navController.currentBackStackEntry ?: return null
        if (current.id != activeEntryId || current.destination.route !in loginRoutes) return null
        val login = try { navController.getBackStackEntry(Screen.AddAccountLogin.route) }
        catch (_: IllegalArgumentException) { return null }
        val origin = login.savedStateHandle.get<String>(ORIGIN_PROCESS) ?: return null
        if (origin == currentProcessId) return null
        val bridge = try { navController.getBackStackEntry(Screen.NotificationAccountBridge.route) }
        catch (_: IllegalArgumentException) { return null }
        return bridge.takeIf { login.savedStateHandle.get<String>(BRIDGE_ENTRY) == it.id }
    }

    private val loginRoutes get() = setOf(Screen.AddAccountLogin.route, Screen.AccountRecoveryHelp.route)

    fun hasNewSession(navController: NavController, currentStamp: String): Boolean {
        val login = try { navController.getBackStackEntry(Screen.AddAccountLogin.route) }
        catch (_: IllegalArgumentException) { return false }
        val initial = login.savedStateHandle.get<String>(ORIGIN_SESSION_STAMP) ?: return false
        return currentStamp != initial
    }

    fun returnToBridge(navController: NavController, signedInUserId: String): Boolean {
        val account = signedInUserId.trim().takeIf(String::isNotEmpty) ?: return false
        if (navController.currentDestination?.route !in loginRoutes) return false
        val login = try { navController.getBackStackEntry(Screen.AddAccountLogin.route) }
        catch (_: IllegalArgumentException) { return false }
        val bridge = try { navController.getBackStackEntry(Screen.NotificationAccountBridge.route) }
        catch (_: IllegalArgumentException) { return false }
        if (login.savedStateHandle.get<String>(BRIDGE_ENTRY) != bridge.id) return false
        if (!navController.popBackStack(Screen.NotificationAccountBridge.route, inclusive = false)) return false
        bridge.savedStateHandle[SIGNED_IN_USER] = account
        return true
    }

    fun result(state: SavedStateHandle) = state.getStateFlow<String?>(SIGNED_IN_USER, null)
    fun takeResult(state: SavedStateHandle): String? = state.get<String>(SIGNED_IN_USER).also {
        // Keep the key so a collector of this StateFlow also sees its consumption.
        state[SIGNED_IN_USER] = null
    }
}
