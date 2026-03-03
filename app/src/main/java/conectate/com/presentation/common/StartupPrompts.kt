package conectate.com.presentation.common

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import conectate.com.presentation.components.BatteryOptimizationDialog
import conectate.com.util.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun StartupPrompts() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionState = rememberPermissionState(android.Manifest.permission.POST_NOTIFICATIONS)
        LaunchedEffect(Unit) {
            if (!permissionState.status.isGranted) {
                permissionState.launchPermissionRequest()
            }
        }
    }

    var showBatteryDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val hasShownBatteryDialog = sharedPrefs.getBoolean("battery_dialog_shown", false)
                if (!hasShownBatteryDialog && DeviceUtils.isProblematicBrand()) {
                    delay(3000)
                    withContext(Dispatchers.Main) {
                        showBatteryDialog = true
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error checking battery optimization", e)
            }
        }
    }

    if (!showBatteryDialog) return

    BatteryOptimizationDialog(
        brandName = DeviceUtils.getBatteryOptimizationGuideName(),
        onDismiss = {
            showBatteryDialog = false
            runCatching {
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("battery_dialog_shown", true)
                    .apply()
            }
        },
        onOpenSettings = {
            runCatching {
                val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(settingsIntent)
            }.onFailure { error ->
                android.util.Log.e("MainActivity", "Error opening battery settings", error)
            }
        }
    )
}
