package conectate.com.presentation.navigation

import androidx.annotation.MainThread
import androidx.navigation.NavController

/** An exit retained across teardown belongs to its original back-stack entry, including equal routes. */
@MainThread
internal fun NavController.popLiveRoute(expectedEntryId: String?, fallbackRoute: String? = null): Boolean {
    if (expectedEntryId == null || currentBackStackEntry?.id != expectedEntryId) return false
    if (popBackStack()) return true
    if (fallbackRoute == null) return false
    navigate(fallbackRoute) { launchSingleTop = true }
    return true
}
