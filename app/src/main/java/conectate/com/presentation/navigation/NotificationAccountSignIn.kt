package conectate.com.presentation.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/** Return only from the login entry opened by this specific notification bridge. */
internal object NotificationAccountSignIn {
    private const val BRIDGE_ENTRY = "notification_sign_in_bridge_entry"
    private const val SIGNED_IN_USER = "notification_signed_in_user"

    fun open(navController: NavController, bridge: NavBackStackEntry) {
        navController.navigate(Screen.AddAccountLogin.route) { launchSingleTop = true }
        navController.currentBackStackEntry?.savedStateHandle?.set(BRIDGE_ENTRY, bridge.id)
    }

    fun returnToBridge(navController: NavController, signedInUserId: String): Boolean {
        val account = signedInUserId.trim().takeIf(String::isNotEmpty) ?: return false
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
