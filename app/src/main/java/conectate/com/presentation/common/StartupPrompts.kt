package conectate.com.presentation.common

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import conectate.com.presentation.components.BatteryOptimizationDialog
import conectate.com.util.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TEST_RUNTIME_PREFS = "test_runtime_prefs"
private const val DISABLE_STARTUP_PROMPTS_KEY = "disable_startup_prompts"
private const val FULL_SCREEN_CALL_PROMPTED_KEY = "full_screen_call_prompted"
private const val NOTIFICATION_PERMISSION_PROMPTED_KEY = "notification_permission_prompted"

@SuppressLint("InlinedApi") // ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT is only reached on API 34+.
@Composable
fun StartupPrompts() {
    val context = LocalContext.current
    val disableStartupPrompts = remember(context) {
        context.getSharedPreferences(TEST_RUNTIME_PREFS, Context.MODE_PRIVATE)
            .getBoolean(DISABLE_STARTUP_PROMPTS_KEY, false)
    }

    if (disableStartupPrompts) return

    val preferences = remember(context) { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var notificationPromptDone by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                preferences.getBoolean(NOTIFICATION_PERMISSION_PROMPTED_KEY, false) ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var showNotificationDialog by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        preferences.edit().putBoolean(NOTIFICATION_PERMISSION_PROMPTED_KEY, true).apply()
        notificationPromptDone = true
    }
    var showFullScreenCallDialog by remember { mutableStateOf(false) }
    LaunchedEffect(notificationPromptDone) {
        if (!notificationPromptDone) {
            delay(1_000L)
            showNotificationDialog = true
            return@LaunchedEffect
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED &&
                !preferences.getBoolean(FULL_SCREEN_CALL_PROMPTED_KEY, false) &&
                !notificationManager.canUseFullScreenIntent()
            ) {
                delay(1_500L)
                showFullScreenCallDialog = true
            }
        }
    }

    var showBatteryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(notificationPromptDone) {
        if (!notificationPromptDone) return@LaunchedEffect
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

    if (showNotificationDialog) {
        AlertDialog(
            onDismissRequest = {
                showNotificationDialog = false
                preferences.edit().putBoolean(NOTIFICATION_PERMISSION_PROMPTED_KEY, true).apply()
                notificationPromptDone = true
            },
            title = { Text("Recibir llamadas y avisos") },
            text = { Text("Activa las notificaciones para que las llamadas entrantes suenen y aparezcan aunque Novvin esté en segundo plano.") },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationDialog = false
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Activar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotificationDialog = false
                    preferences.edit().putBoolean(NOTIFICATION_PERMISSION_PROMPTED_KEY, true).apply()
                    notificationPromptDone = true
                }) { Text("Ahora no") }
            }
        )
        return
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







