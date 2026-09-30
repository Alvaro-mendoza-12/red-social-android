package conectate.com.presentation.calls

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import conectate.com.R

@Composable
fun IncomingCallPermissionCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val manager = context.getSystemService(NotificationManager::class.java)
    fun status(): Pair<Boolean, Boolean> {
        val channel = if (Build.VERSION.SDK_INT >= 26) manager.getNotificationChannel(context.getString(R.string.call_notification_channel_id)) else null
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() && (channel == null || channel.importance >= NotificationManager.IMPORTANCE_HIGH)
        val fullscreen = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
        return notifications to fullscreen
    }
    var state by remember { mutableStateOf(status()) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) state = status() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (state.first && state.second) return
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Contestar llamadas fuera de Novvin", style = MaterialTheme.typography.titleMedium)
            Text("Activa los avisos de llamadas para ver Contestar y Rechazar. La pantalla completa permite mostrarlos también con el teléfono bloqueado.",
                style = MaterialTheme.typography.bodyMedium)
            if (!state.first) OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                    .onFailure { error = "Abre Ajustes de Android → Novvin → Notificaciones → Llamadas." }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Activar avisos de llamadas") }
            if (!state.second && Build.VERSION.SDK_INT >= 34) OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))) }
                    .onFailure { error = "Abre Ajustes de Android → Acceso especial → Notificaciones en pantalla completa → Novvin." }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Permitir pantalla de llamada") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
