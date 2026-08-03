package conectate.com.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import conectate.com.data.local.prefs.AppFontOption
import conectate.com.data.local.prefs.MessagesPreferences
import conectate.com.data.local.prefs.ThemePreferences
import conectate.com.presentation.components.AppAppearanceSettingsSection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// Colors now resolved via MaterialTheme.colorScheme for light/dark compatibility

@HiltViewModel
class ChatSettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
    private val messagesPreferences: MessagesPreferences
) : ViewModel() {

    val isDarkMode = themePreferences.isDarkMode
    val fontOption = themePreferences.fontOption
    val textScale = themePreferences.textScale
    val isEnterToSendEnabled = messagesPreferences.enterToSendEnabled
    val isMediaAutoDownloadEnabled = messagesPreferences.mediaAutoDownloadEnabled
    val isReadReceiptsEnabled = messagesPreferences.readReceiptsEnabled
    val chatSoundsEnabled = messagesPreferences.chatSoundsEnabled
    val typingIndicatorsEnabled = messagesPreferences.typingIndicatorsEnabled
    val compactMode = messagesPreferences.compactMode
    val connectivityHintsEnabled = messagesPreferences.connectivityHintsEnabled
    val chatAccent = messagesPreferences.chatAccent
    val chatBackground = messagesPreferences.chatBackground

    fun setDarkMode(enabled: Boolean) = themePreferences.setDarkMode(enabled)
    fun setFontOption(option: AppFontOption) = themePreferences.setFontOption(option)
    fun setTextScale(scale: Float) = themePreferences.setTextScale(scale)
    fun toggleEnterToSend(enabled: Boolean) = messagesPreferences.setEnterToSendEnabled(enabled)
    fun toggleMediaAutoDownload(enabled: Boolean) = messagesPreferences.setMediaAutoDownloadEnabled(enabled)
    fun toggleReadReceipts(enabled: Boolean) = messagesPreferences.setReadReceiptsEnabled(enabled)
    fun toggleChatSounds(enabled: Boolean) = messagesPreferences.setChatSoundsEnabled(enabled)
    fun toggleTypingIndicators(enabled: Boolean) = messagesPreferences.setTypingIndicatorsEnabled(enabled)
    fun toggleCompactMode(enabled: Boolean) = messagesPreferences.setCompactMode(enabled)
    fun toggleConnectivityHints(enabled: Boolean) = messagesPreferences.setConnectivityHintsEnabled(enabled)
    fun setChatAccent(value: String) = messagesPreferences.setChatAccent(value)
    fun setChatBackground(value: String) = messagesPreferences.setChatBackground(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    navController: NavController,
    viewModel: ChatSettingsViewModel = hiltViewModel()
) {
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val fontOption by viewModel.fontOption.collectAsState()
    val textScale by viewModel.textScale.collectAsState()
    val isEnterToSendEnabled by viewModel.isEnterToSendEnabled.collectAsState()
    val isMediaAutoDownloadEnabled by viewModel.isMediaAutoDownloadEnabled.collectAsState()
    val isReadReceiptsEnabled by viewModel.isReadReceiptsEnabled.collectAsState()
    val chatSoundsEnabled by viewModel.chatSoundsEnabled.collectAsState()
    val typingIndicatorsEnabled by viewModel.typingIndicatorsEnabled.collectAsState()
    val compactMode by viewModel.compactMode.collectAsState()
    val connectivityHintsEnabled by viewModel.connectivityHintsEnabled.collectAsState()
    val chatAccent by viewModel.chatAccent.collectAsState()
    val chatBackground by viewModel.chatBackground.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Personalizar mensajes",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsPanel {
                AppAppearanceSettingsSection(
                    isDarkMode = isDarkMode,
                    fontOption = fontOption,
                    textScale = textScale,
                    onDarkModeChanged = viewModel::setDarkMode,
                    onFontOptionChanged = viewModel::setFontOption,
                    onTextScaleChanged = viewModel::setTextScale,
                    title = "Estilo de la app",
                    subtitle = "Tema, tipografía y tamaño que se aplican en Novvin."
                )
            }

            SettingsPanel {
                SectionTitle("Comportamiento del chat")
                SettingSwitchRow(
                    icon = Icons.Default.VolumeUp,
                    title = "Sonidos de chat",
                    subtitle = "Tonos de mensajes y celebraciones de regalos en directos.",
                    checked = chatSoundsEnabled,
                    onCheckedChange = viewModel::toggleChatSounds
                )
                SettingDivider()
                SettingSwitchRow(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "Indicador escribiendo",
                    subtitle = "Muestra cuando otra persona está escribiendo.",
                    checked = typingIndicatorsEnabled,
                    onCheckedChange = viewModel::toggleTypingIndicators
                )
                SettingDivider()
                SettingSwitchRow(
                    icon = Icons.Default.MarkChatRead,
                    title = "Confirmaciones de lectura",
                    subtitle = "Controla el visto en conversaciones compatibles.",
                    checked = isReadReceiptsEnabled,
                    onCheckedChange = viewModel::toggleReadReceipts
                )
                SettingDivider()
                SettingSwitchRow(
                    icon = Icons.Default.KeyboardReturn,
                    title = "Enter para enviar",
                    subtitle = "La tecla Enter enviará el mensaje cuando uses teclado.",
                    checked = isEnterToSendEnabled,
                    onCheckedChange = viewModel::toggleEnterToSend
                )
            }

            SettingsPanel {
                SectionTitle("Datos y bandeja")
                SettingSwitchRow(
                    icon = Icons.Default.Download,
                    title = "Descarga automática",
                    subtitle = "Precarga fotos, audios y videos de chats.",
                    checked = isMediaAutoDownloadEnabled,
                    onCheckedChange = viewModel::toggleMediaAutoDownload
                )
                SettingDivider()
                SettingSwitchRow(
                    icon = Icons.Default.Palette,
                    title = "Bandeja compacta",
                    subtitle = "Reduce espacios para ver más conversaciones.",
                    checked = compactMode,
                    onCheckedChange = viewModel::toggleCompactMode
                )
                SettingDivider()
                SettingSwitchRow(
                    icon = Icons.Default.Wallpaper,
                    title = "Avisos de conexión",
                    subtitle = "Muestra señales de conexión y reconexión en chats.",
                    checked = connectivityHintsEnabled,
                    onCheckedChange = viewModel::toggleConnectivityHints
                )
            }

            SettingsPanel {
                SectionTitle("Color del chat")
                ChoiceRow(
                    options = listOf(
                        ChatChoice("conectate_red", "Rojo"),
                        ChatChoice("clean_white", "Blanco"),
                        ChatChoice("electric_blue", "Azul"),
                        ChatChoice("graphite", "Grafito")
                    ),
                    selected = chatAccent,
                    onSelected = viewModel::setChatAccent
                )
                Spacer(modifier = Modifier.height(14.dp))
                SectionTitle("Fondo de conversación")
                ChoiceRow(
                    options = listOf(
                        ChatChoice("deep_black", "Negro"),
                        ChatChoice("soft_noise", "Textura"),
                        ChatChoice("midnight", "Noche"),
                        ChatChoice("minimal", "Limpio")
                    ),
                    selected = chatBackground,
                    onSelected = viewModel::setChatBackground
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFE53935)
            )
        },
        headlineContent = {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun SettingDivider() {
    Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
}

private data class ChatChoice(
    val key: String,
    val label: String
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChoiceRow(
    options: List<ChatChoice>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option.key == selected,
                onClick = { onSelected(option.key) },
                label = { Text(option.label) }
            )
        }
        AssistChip(
            onClick = { onSelected(options.first().key) },
            label = { Text("Restablecer") }
        )
        Spacer(modifier = Modifier.width(4.dp))
    }
}
