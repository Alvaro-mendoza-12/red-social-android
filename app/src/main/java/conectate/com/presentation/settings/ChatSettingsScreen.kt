package conectate.com.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
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
import conectate.com.data.local.prefs.MessagesPreferences
import conectate.com.presentation.navigation.Screen

@HiltViewModel
class ChatSettingsViewModel @Inject constructor(
    private val messagesPreferences: MessagesPreferences
) : ViewModel() {
    val isEnterToSendEnabled: StateFlow<Boolean> = messagesPreferences.enterToSendEnabled
    val isMediaAutoDownloadEnabled: StateFlow<Boolean> = messagesPreferences.mediaAutoDownloadEnabled
    val isReadReceiptsEnabled: StateFlow<Boolean> = messagesPreferences.readReceiptsEnabled

    fun toggleEnterToSend(enabled: Boolean) {
        messagesPreferences.setEnterToSendEnabled(enabled)
    }

    fun toggleMediaAutoDownload(enabled: Boolean) {
        messagesPreferences.setMediaAutoDownloadEnabled(enabled)
    }

    fun toggleReadReceipts(enabled: Boolean) {
        messagesPreferences.setReadReceiptsEnabled(enabled)
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
                supportingContent = { Text("Personaliza tema, fuente y escala del texto") },
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    navController.navigate(Screen.ThemeSettings.route)
                }
            )
            Divider()

            ListItem(
                headlineContent = { Text("Sincronización y almacenamiento") },
                supportingContent = { Text("Controla datos, descargas y rendimiento de mensajes") },
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    navController.navigate(Screen.StorageSettings.route)
                }
            )
        }
    }
}
