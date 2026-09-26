package conectate.com.presentation.settings

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
import conectate.com.core.media.ChatOfflineMediaUsage
import conectate.com.data.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ChatOfflineStorageViewModel @Inject constructor(
    private val messages: MessageRepository
) : ViewModel() {
    private val _usage = MutableStateFlow<ChatOfflineMediaUsage?>(null)
    val usage = _usage.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _usage.value = messages.getOfflineChatMediaUsage() }
    }

    fun clear(expectedAccountKey: String) {
        if (!_busy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                _notice.value = if (messages.clearOfflineChatMedia(expectedAccountKey)) {
                    "Copias locales eliminadas. Los mensajes y archivos del servidor no se borraron."
                } else {
                    "No se pudo liberar el espacio o cambió la cuenta. Vuelve a comprobarlo."
                }
                _usage.value = messages.getOfflineChatMediaUsage()
            } finally {
                _busy.value = false
            }
        }
    }
}

@Composable
internal fun ChatOfflineStorageSection(viewModel: ChatOfflineStorageViewModel = hiltViewModel()) {
    val usage by viewModel.usage.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val notice by viewModel.notice.collectAsState()
    var confirmation by remember { mutableStateOf<ChatOfflineMediaUsage?>(null) }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.refresh() }

    Column {
        Text("Archivos de chats sin conexión", style = MaterialTheme.typography.titleSmall)
        Text(
            "Las copias de fotos, audios y videos que abres se conservan en este teléfono. " +
                "La precarga aún no abierta es temporal. No incluye archivos de ver una vez.",
            style = MaterialTheme.typography.bodySmall
        )
        usage?.let { current ->
            val kept = android.text.format.Formatter.formatShortFileSize(context, current.retainedBytes)
            val prefetch = android.text.format.Formatter.formatShortFileSize(context, current.prefetchedBytes)
            Text("${current.fileCount} archivos · Conservados: $kept · Precarga: $prefetch")
        }
        OutlinedButton(
            onClick = { confirmation = usage },
            enabled = !busy && (usage?.fileCount ?: 0) > 0,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Liberando espacio…" else "Liberar copias locales") }
        TextButton(onClick = viewModel::refresh, enabled = !busy) { Text("Actualizar espacio usado") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    ProfileOfflineStorageSection()
    confirmation?.let { current ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("¿Eliminar las copias de esta cuenta?") },
            text = {
                Text(
                    "Estos archivos dejarán de estar disponibles sin conexión. Necesitarás descargarlos " +
                        "de nuevo si todavía existen en el servidor. No se borran conversaciones, " +
                        "archivos de la galería ni copias de otras cuentas."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    viewModel.clear(current.accountKey)
                }) { Text("Eliminar copias") }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text("Cancelar") }
            }
        )
    }
}
