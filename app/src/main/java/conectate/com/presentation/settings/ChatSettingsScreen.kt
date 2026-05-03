package conectate.com.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import conectate.com.data.local.prefs.ThemePreferences
import kotlinx.coroutines.launch

@HiltViewModel
class ChatSettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences
) : ViewModel() {

    private val _isEnterToSendEnabled = MutableStateFlow(false)
    val isEnterToSendEnabled: StateFlow<Boolean> = _isEnterToSendEnabled.asStateFlow()

    private val _isMediaAutoDownloadEnabled = MutableStateFlow(true)
    val isMediaAutoDownloadEnabled: StateFlow<Boolean> = _isMediaAutoDownloadEnabled.asStateFlow()

    private val _isReadReceiptsEnabled = MutableStateFlow(true)
    val isReadReceiptsEnabled: StateFlow<Boolean> = _isReadReceiptsEnabled.asStateFlow()

    fun toggleEnterToSend(enabled: Boolean) {
        _isEnterToSendEnabled.value = enabled
    }

    fun toggleMediaAutoDownload(enabled: Boolean) {
        _isMediaAutoDownloadEnabled.value = enabled
    }

    fun toggleReadReceipts(enabled: Boolean) {
        _isReadReceiptsEnabled.value = enabled
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    navController: NavController,
    viewModel: ChatSettingsViewModel = hiltViewModel()
) {
    val isEnterToSendEnabled by viewModel.isEnterToSendEnabled.collectAsState()
    val isMediaAutoDownloadEnabled by viewModel.isMediaAutoDownloadEnabled.collectAsState()
    val isReadReceiptsEnabled by viewModel.isReadReceiptsEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes de Chats") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ListItem(
                headlineContent = { Text("Enter para enviar") },
                supportingContent = { Text("La tecla Enter enviará tu mensaje") },
                trailingContent = {
                    Switch(
                        checked = isEnterToSendEnabled,
                        onCheckedChange = { viewModel.toggleEnterToSend(it) }
                    )
                }
            )
            Divider()
            
            ListItem(
                headlineContent = { Text("Descarga automática de multimedia") },
                supportingContent = { Text("Descargar fotos y videos automáticamente") },
                trailingContent = {
                    Switch(
                        checked = isMediaAutoDownloadEnabled,
                        onCheckedChange = { viewModel.toggleMediaAutoDownload(it) }
                    )
                }
            )
            Divider()

            ListItem(
                headlineContent = { Text("Confirmaciones de lectura") },
                supportingContent = { Text("Si desactivas esto, no podrás ver las confirmaciones de lectura de los demás") },
                trailingContent = {
                    Switch(
                        checked = isReadReceiptsEnabled,
                        onCheckedChange = { viewModel.toggleReadReceipts(it) }
                    )
                }
            )
            Divider()

            ListItem(
                headlineContent = { Text("Fondo de pantalla de chat") },
                supportingContent = { Text("Cambiar el fondo de tus conversaciones") },
                modifier = Modifier.clickable { /* TODO */ }
            )
            Divider()

            ListItem(
                headlineContent = { Text("Copia de seguridad") },
                supportingContent = { Text("Configurar backups de tus mensajes") },
                modifier = Modifier.clickable { /* TODO */ }
            )
        }
    }
}
