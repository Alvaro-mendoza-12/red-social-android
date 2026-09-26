package conectate.com.presentation.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.core.media.ProfileIdentityImageStore
import conectate.com.core.media.ProfileIdentityUsage
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.components.LocalProfileIdentityAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ProfileOfflineStorageViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: AuthService,
) : ViewModel() {
    private val _usage = MutableStateFlow<ProfileIdentityUsage?>(null)
    val usage = _usage.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private var revision = 0L

    fun refresh() {
        val requestRevision = ++revision
        val account = auth.getCurrentUserId().orEmpty()
        _usage.value = null
        viewModelScope.launch {
            val loaded = ProfileIdentityImageStore.usage(context, account)
            if (revision == requestRevision && auth.getCurrentUserId().orEmpty() == account) _usage.value = loaded
        }
    }

    fun clear(expected: ProfileIdentityUsage) {
        if (!_busy.compareAndSet(false, true)) return
        revision++
        _usage.value = null
        val account = auth.getCurrentUserId().orEmpty()
        viewModelScope.launch {
            try {
                _notice.value = if (ProfileIdentityImageStore.clear(context, account, expected.accountKey)) {
                    "Miniaturas locales eliminadas. Las fotos de perfil del servidor no se borraron."
                } else "Cambió la cuenta o no se pudo liberar el espacio. Vuelve a comprobarlo."
                refresh()
            } finally { _busy.value = false }
        }
    }
}

@Composable
internal fun ProfileOfflineStorageSection(viewModel: ProfileOfflineStorageViewModel = hiltViewModel()) {
    val usage by viewModel.usage.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val account = LocalProfileIdentityAccount.current
    var confirmation by remember(account) { mutableStateOf<ProfileIdentityUsage?>(null) }
    val context = LocalContext.current
    LaunchedEffect(account, viewModel) { viewModel.refresh() }
    Column {
        Text("Fotos de perfil sin conexión", style = MaterialTheme.typography.titleSmall)
        Text("Se conservan miniaturas de los perfiles vistos y de la bandeja, separadas por cuenta. No son una copia de los videos animados completos.", style = MaterialTheme.typography.bodySmall)
        usage?.let { Text("${it.imageCount} miniaturas · ${android.text.format.Formatter.formatShortFileSize(context, it.bytes)}") }
        OutlinedButton(
            onClick = { confirmation = usage },
            enabled = !busy && (usage?.imageCount ?: 0) > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Liberando espacio…" else "Eliminar miniaturas locales") }
        TextButton(onClick = viewModel::refresh, enabled = !busy) { Text("Actualizar espacio usado") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    confirmation?.let { confirmed ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("¿Eliminar miniaturas de esta cuenta?") },
            text = { Text("Se eliminarán estas copias para uso sin conexión. Las imágenes pueden guardarse otra vez al volver a ver los perfiles. No se borran fotos del servidor, mensajes, galería ni copias de otras cuentas.") },
            confirmButton = { TextButton(onClick = { confirmation = null; viewModel.clear(confirmed) }) { Text("Eliminar miniaturas") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancelar") } },
        )
    }
}
