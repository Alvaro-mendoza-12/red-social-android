package conectate.com.presentation.profile

import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen

/** Profile can live inside Home; conversations always use its supplied root graph. */
internal fun openProfileConversation(controller: NavController, userId: String, onError: () -> Unit): Boolean {
    val identity = userId.trim()
    if (identity.isEmpty() || identity.any { it.isISOControl() }) {
        onError()
        return false
    }
    return try {
        controller.navigate(Screen.Chat.createRoute(identity)) { launchSingleTop = true }
        true
    } catch (_: IllegalArgumentException) {
        // A stale graph/destination must not crash the Activity.
        onError()
        false
    } catch (_: IllegalStateException) {
        onError()
        false
    }
}
