package conectate.com.presentation.common

import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import conectate.com.presentation.components.BatteryOptimizationDialog
import conectate.com.util.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TEST_RUNTIME_PREFS = "test_runtime_prefs"
private const val DISABLE_STARTUP_PROMPTS_KEY = "disable_startup_prompts"
private const val FULL_SCREEN_CALL_PROMPTED_KEY = "full_screen_call_prompted"

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun StartupPrompts() {
    val context = LocalContext.current
    val disableStartupPrompts = remember(context) {
        context.getSharedPreferences(TEST_RUNTIME_PREFS, Context.MODE_PRIVATE)
            .getBoolean(DISABLE_STARTUP_PROMPTS_KEY, false)
    }

    if (disableStartupPrompts) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionState = rememberPermissionState(android.Manifest.permission.POST_NOTIFICATIONS)
        LaunchedEffect(Unit) {
            if (!permissionState.status.isGranted) {
                permissionState.launchPermissionRequest()
            }
        }
    }

    var showFullScreenCallDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val preferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            if (
                !preferences.getBoolean(FULL_SCREEN_CALL_PROMPTED_KEY, false) &&
                !notificationManager.canUseFullScreenIntent()
            ) {
                delay(1_500L)
                showFullScreenCallDialog = true
            }
        }
    }

    var showBatteryDialog by remember { mutableStateOf(false) }

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

    if (showFullScreenCallDialog) {
        fun finishFullScreenPrompt() {
            showFullScreenCallDialog = false
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(FULL_SCREEN_CALL_PROMPTED_KEY, true)
                .apply()
        }
        AlertDialog(
            onDismissRequest = { finishFullScreenPrompt() },
            title = { Text("Recibir llamadas en pantalla completa") },
            text = {
                Text("Android necesita que autorices las alertas de llamada a pantalla completa para que aparezcan incluso con el teléfono bloqueado.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }.onFailure { error ->
                            android.util.Log.e("StartupPrompts", "Could not open full-screen call settings", error)
                        }
                        finishFullScreenPrompt()
                    }
                ) { Text("Autorizar") }
            },
            dismissButton = {
                TextButton(onClick = { finishFullScreenPrompt() }) { Text("Ahora no") }
            }
        )
        return
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







