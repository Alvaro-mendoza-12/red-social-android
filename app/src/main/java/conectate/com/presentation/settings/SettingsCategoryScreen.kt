package conectate.com.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import conectate.com.data.local.prefs.AppSettingsPreferences
import conectate.com.presentation.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsCategoryViewModel @Inject constructor(
    private val preferences: AppSettingsPreferences,
) : ViewModel() {
    val messageNotifications = preferences.messageNotificationsEnabled
    val callNotifications = preferences.callNotificationsEnabled
    val socialNotifications = preferences.socialNotificationsEnabled
    val liveNotifications = preferences.liveNotificationsEnabled
    val livesInVideoFeed = preferences.livesInVideoFeedEnabled
    val livesInSocial = preferences.livesInSocialEnabled

    fun setMessageNotifications(value: Boolean) = preferences.setMessageNotificationsEnabled(value)
    fun setCallNotifications(value: Boolean) = preferences.setCallNotificationsEnabled(value)
    fun setSocialNotifications(value: Boolean) = preferences.setSocialNotificationsEnabled(value)
    fun setLiveNotifications(value: Boolean) = preferences.setLiveNotificationsEnabled(value)
    fun setLivesInVideoFeed(value: Boolean) = preferences.setLivesInVideoFeedEnabled(value)
    fun setLivesInSocial(value: Boolean) = preferences.setLivesInSocialEnabled(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCategoryScreen(
    navController: NavController,
    mainNavController: NavController = navController,
    category: String,
    viewModel: SettingsCategoryViewModel = hiltViewModel(),
) {
    val normalized = category.trim().lowercase()
    if (normalized == "algorithm") {
        conectate.com.presentation.algorithm.AlgorithmSettingsScreen(navController = navController)
        return
    }
    if (normalized == "socialization") {
        conectate.com.presentation.socialization.SocializationSettingsScreen(navController = navController)
        return
    }
    val title = when (normalized) {
        "messages" -> "Mensajes y llamadas"
        "videos" -> "Videos y multimedia"
        "social" -> "Social"
        else -> "Perfil y apariencia"
    }
    val messageNotifications by viewModel.messageNotifications.collectAsState()
    val callNotifications by viewModel.callNotifications.collectAsState()
    val socialNotifications by viewModel.socialNotifications.collectAsState()
    val liveNotifications by viewModel.liveNotifications.collectAsState()
    val livesInVideoFeed by viewModel.livesInVideoFeed.collectAsState()
    val livesInSocial by viewModel.livesInSocial.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Todo lo de $title, reunido en un solo lugar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (normalized) {
                "messages" -> {
                    item {
                        SettingToggleCard(
                            "Notificaciones de mensajes",
                            "Avisos de mensajes directos, grupos y comunidades.",
                            messageNotifications,
                            viewModel::setMessageNotifications,
                        )
                    }
                    item {
                        SettingToggleCard(
                            "Notificaciones de llamadas",
                            "Llamadas de voz y video entrantes.",
                            callNotifications,
                            viewModel::setCallNotifications,
                        )
                    }
                    item {
                        SettingsDestination(
                            Icons.Default.Message,
                            "Preferencias de chat",
                            "Apariencia, sonidos, lectura, escritura y descargas.",
                        ) { navController.navigate(Screen.ChatSettings.route) }
                    }
                    item {
                        SettingsDestination(
                            Icons.Default.Notifications,
                            "Resumen de notificaciones",
                            "Horario y frecuencia de avisos no urgentes.",
                        ) { navController.navigate(Screen.NotificationSettings.route) }
                    }
                }
                "videos" -> {
                    item {
                        SettingToggleCard(
                            "Directos intercalados",
                            "Muestra un directo recomendado cada 10 a 15 videos.",
                            livesInVideoFeed,
                            viewModel::setLivesInVideoFeed,
                        )
                    }
                    item {
                        SettingsDestination(
                            Icons.Default.Download,
                            "Videos descargados",
                            "Administra el contenido disponible sin conexión.",
                        ) { navController.navigate(Screen.OfflineVideos.route) }
                    }
                    item {
                        SettingsDestination(
                            Icons.Default.Message,
                            "Descarga automática de multimedia",
                            "Configura la carga automática de archivos recibidos.",
                        ) { navController.navigate(Screen.ChatSettings.route) }
                    }
                }
                "social" -> {
                    item {
                        SettingsDestination(
                            Icons.Default.Person,
                            "Socializar",
                            "Decide voluntariamente qué experiencias sociales públicas quieres descubrir.",
                        ) { mainNavController.navigate(Screen.SettingsCategory.createRoute("socialization")) }
                    }
                    item {
                        SettingsDestination(
                            Icons.Default.Palette,
                            "Mi algoritmo",
                            "Elige temas, creadores, mezcla del feed y datos que autorizas usar.",
                        ) { mainNavController.navigate(Screen.SettingsCategory.createRoute("algorithm")) }
                    }
                    item {
                        SettingToggleCard(
                            "Directos en Social",
                            "Muestra los directos activos en la parte superior de Social.",
                            livesInSocial,
                            viewModel::setLivesInSocial,
                        )
                    }
                    item {
                        SettingToggleCard(
                            "Actividad social",
                            "Amistades, comentarios, rondas y comunidades.",
                            socialNotifications,
                            viewModel::setSocialNotifications,
                        )
                    }
                    item {
                        SettingToggleCard(
                            "Directos y salas",
                            "Avisos de transmisiones y actividad en vivo.",
                            liveNotifications,
                            viewModel::setLiveNotifications,
                        )
                    }
                    item {
                        SettingsDestination(
                            Icons.Default.Radar,
                            "Radar y chat aleatorio",
                            "Radio de búsqueda y preferencias de conexión.",
                        ) { mainNavController.navigate(Screen.Proximity.route) }
                    }
                    item {
                        SettingsDestination(
                            Icons.Default.Report,
                            "Mis reportes",
                            "Consulta el estado de tus reportes enviados.",
                        ) { mainNavController.navigate(Screen.MyReports.route) }
                    }
                }
                else -> {
                    item {
                        SettingsDestination(
                            Icons.Default.Person,
                            "Datos del perfil",
                            "Foto, nombre, descripción y datos personales.",
                        ) { navController.navigate(Screen.AccountSettings.route) }
                    }
                    item {
                        SettingsDestination(
                            Icons.Default.Palette,
                            "Personalización",
                            "Temas, tipografía, efectos y ambiente del perfil.",
                        ) { navController.navigate(Screen.CustomizeApp.route) }
                    }
                    item {
                        SettingsDestination(
                            Icons.Default.Lock,
                            "Cambiar contraseña",
                            "Actualiza la contraseña de acceso de forma segura.",
                        ) { mainNavController.navigate(Screen.ChangePassword.route) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(subtitle) },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun SettingsDestination(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
    ) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
