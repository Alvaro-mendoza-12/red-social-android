package conectate.com.presentation.messages

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen

internal fun openMessageWebScanner(navigate: (String) -> Unit, onError: () -> Unit): Boolean = try {
    navigate(Screen.WebLoginScanner.createRoute())
    true
} catch (_: IllegalArgumentException) {
    onError()
    false
} catch (_: IllegalStateException) {
    onError()
    false
}

@Composable
internal fun MessageWebScannerButton(navController: NavController, onError: () -> Unit) {
    IconButton(
        onClick = { openMessageWebScanner({ route -> navController.navigate(route) { launchSingleTop = true } }, onError) },
        modifier = Modifier.size(48.dp).testTag("message-web-scanner")
    ) {
        Icon(Icons.Default.QrCodeScanner, contentDescription = "Vincular Novvin Web", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
