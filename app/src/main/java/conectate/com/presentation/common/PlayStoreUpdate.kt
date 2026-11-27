package conectate.com.presentation.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import conectate.com.BuildConfig
import conectate.com.data.localfallback.SystemAnnouncement
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@HiltViewModel
internal class PlayStoreUpdateViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : ViewModel(), DefaultLifecycleObserver {
    private val reminder = PlayStoreUpdateReminder(viewModelScope, BuildConfig.VERSION_CODE) {
        suspendCancellableCoroutine { continuation ->
            AppUpdateManagerFactory.create(context).appUpdateInfo.addOnCompleteListener { task ->
                val info = if (task.isSuccessful) task.result else null
                val version = info?.takeIf {
                    it.packageName() == context.packageName &&
                        it.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                }?.availableVersionCode()
                if (continuation.isActive) continuation.resume(version)
            }
        }
    }

    val availableVersion = reminder.availableVersion
    private val processLifecycle = ProcessLifecycleOwner.get().lifecycle

    init { processLifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) = reminder.enterForeground()
    override fun onStop(owner: LifecycleOwner) = reminder.leaveForeground()

    fun dismiss() = reminder.dismiss()

    override fun onCleared() {
        processLifecycle.removeObserver(this)
        reminder.leaveForeground()
    }
}

@Composable
internal fun PlayStoreUpdateDialog(notice: SystemAnnouncement?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.Top) {
                Text("Nueva actualización disponible", Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cerrar actualización") }
            }
        },
        text = {
            Column(
                Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Ya puedes instalar una nueva versión de Novvin desde Google Play.")
                if (notice != null) {
                    Text(notice.title)
                    Text(notice.body)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (openGooglePlay(context)) onDismiss()
                else Toast.makeText(context, "No se pudo abrir Google Play. Inténtalo de nuevo.", Toast.LENGTH_LONG).show()
            }) { Text("Actualizar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun openGooglePlay(context: Context): Boolean {
    val storeUri = Uri.parse("market://details").buildUpon().appendQueryParameter("id", context.packageName).build()
    val store = Intent(Intent.ACTION_VIEW, storeUri).setPackage("com.android.vending")
    if (runCatching { context.startActivity(store) }.isSuccess) return true

    val webUri = Uri.parse("https://play.google.com/store/apps/details").buildUpon()
        .appendQueryParameter("id", context.packageName).build()
    return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, webUri)) }.isSuccess
}
