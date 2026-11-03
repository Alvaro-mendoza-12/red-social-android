package conectate.com.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import conectate.com.presentation.auth.AuthState
import conectate.com.presentation.auth.AuthViewModel
import conectate.com.presentation.common.NotificationAccountBridgeViewModel

/** A fresh manual login must remain usable even when the expired recipient is still active. */
@Composable
internal fun RestoreNotificationSignIn(
    navController: NavHostController,
    loginEntry: NavBackStackEntry,
    authViewModel: AuthViewModel,
) {
    val bridge = remember(navController, loginEntry) {
        NotificationAccountSignIn.restoredBridge(navController, loginEntry.id)
    } ?: return
    val bridgeViewModel: NotificationAccountBridgeViewModel = hiltViewModel(bridge)
    val targetAccount = bridge.arguments?.getString("targetAccountId")
    val targetUser = bridge.arguments?.getString("targetUserId")
    val targetEmail = bridge.arguments?.getString("targetEmail")
    val recipient by remember(bridgeViewModel, targetAccount, targetUser, targetEmail) {
        bridgeViewModel.observeRestoredSignInSession(targetAccount, targetUser, targetEmail)
    }.collectAsStateWithLifecycle(initialValue = null)
    val authState by authViewModel.authState.collectAsStateWithLifecycle()

    LaunchedEffect(authState, recipient) {
        if (authState != AuthState.Idle) return@LaunchedEffect
        val observed = recipient ?: return@LaunchedEffect
        if (NotificationAccountSignIn.restoredBridge(navController, loginEntry.id)?.id != bridge.id) return@LaunchedEffect
        val current = bridgeViewModel.currentRestoredSignInSession(targetAccount, targetUser, targetEmail)
        // The original expired session is not proof that this manual login completed.
        if (current == observed && NotificationAccountSignIn.hasNewSession(navController, observed.credentialStamp)) {
            NotificationAccountSignIn.returnToBridge(navController, observed.userId)
        }
    }
}
