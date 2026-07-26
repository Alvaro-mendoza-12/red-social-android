package conectate.com.presentation.settings

import androidx.compose.runtime.Composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.BuildConfig
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val user by viewModel.user.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues
        ) {
            // Profile section
            item {
                if (user != null) {
                    ProfileSection(
                        userName = user!!.name,
                        userHandle = "@${user!!.username}",
                        avatarUrl = user!!.profileImageUrl,
                        onClick = {
                            navController.navigate(Screen.UserProfile.createRoute(user!!.id))
                        }
                    )
                }
            }
            
            // Account section
            item {
                SettingsSectionTitle("Cuenta")
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Person,
                    title = "Información personal",
                    subtitle = "Nombre, foto de perfil, biografía",
                    onClick = { navController.navigate(Screen.AccountSettings.route) }
                )
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Security,
                    title = "Seguridad",
                    subtitle = "Contraseña, autenticación de dos factores",
                    onClick = { navController.navigate(Screen.SecuritySettings.route) }
                )
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Lock,
                    title = "Privacidad",
                    subtitle = "Visibilidad del perfil, bloqueos",
                    onClick = { navController.navigate(Screen.PrivacySettings.route) }
                )
            }
            
            // Connections and Integrations section
            item {
                SettingsSectionTitle("Conexiones e Integraciones")
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Share,
                    title = "Redes Sociales",
                    subtitle = "Vincular Instagram, Twitter, TikTok",
                    onClick = { /* Navigate to Social Settings */ }
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.SportsEsports,
                    title = "Juegos y Consolas",
                    subtitle = "Vincular PlayStation, Xbox, Steam",
                    onClick = { /* Navigate to Games Settings */ }
                )
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.MusicNote,
                    title = "Plataformas de Música",
                    subtitle = "Vincular Spotify, Apple Music",
                    onClick = { /* Navigate to Music Settings */ }
                )
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Tv,
                    title = "Cuentas de Anime",
                    subtitle = "Vincular Crunchyroll, MyAnimeList",
                    onClick = { /* Navigate to Anime Settings */ }
                )
            }
            
            // App settings section
            item {
                SettingsSectionTitle("Preferencias")
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.DashboardCustomize,
                    title = "Personalizar App",
                    subtitle = "Cambiar orden de pestañas y temas",
                    onClick = { navController.navigate(Screen.CustomizeApp.route) }
                )
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Notifications,
                    title = "Notificaciones",
                    subtitle = "Mensajes, llamadas, publicaciones",
                    onClick = { navController.navigate(Screen.NotificationSettings.route) }
                )
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Chat,
                    title = "Chats",
                    subtitle = "Tema, fondos de pantalla, historial",
                    onClick = { navController.navigate(Screen.ChatSettings.route) }
                )
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Storage,
                    title = "Almacenamiento y datos",
                    subtitle = "Uso de red, descarga automática",
                    onClick = { navController.navigate(Screen.StorageSettings.route) }
                )
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = "Idioma",
                    subtitle = "Español (idioma del dispositivo)",
                    onClick = { navController.navigate(Screen.LanguageSettings.route) }
                )
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.DarkMode,
                    title = "Tema",
                    subtitle = "Modo oscuro/claro",
                    onClick = { navController.navigate(Screen.ThemeSettings.route) }
                )
            }
            
            // Help section
            item {
                SettingsSectionTitle("Ayuda")
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Help,
                    title = "Centro de ayuda",
                    subtitle = "Preguntas frecuentes, contacto",
                    onClick = { navController.navigate(Screen.HelpSettings.route) }
                )
            }
            
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "Acerca de",
                    subtitle = "Versión ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), políticas",
                    onClick = { navController.navigate(Screen.AboutSettings.route) }
                )
            }
            
            // Account actions
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            item {
                ListItem(
                    headlineContent = { 
                        Text(
                            "Cerrar sesión",
                            color = MaterialTheme.colorScheme.error
                        ) 
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable { showLogoutDialog = true }
                )
            }
            
            item {
                ListItem(
                    headlineContent = { 
                        Text(
                            "Eliminar cuenta",
                            color = MaterialTheme.colorScheme.error
                        ) 
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable { /* Show delete account dialog */ }
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    
    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Cerrar sesión") },
            text = { Text("¿Estás seguro de que quieres cerrar sesión?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.logout()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                ) {
                    Text("Cerrar sesión", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun ProfileSection(
    userName: String,
    userHandle: String,
    avatarUrl: String?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProfileImage(
                imageUrl = avatarUrl,
                size = 64.dp
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = userHandle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Divider()
}

@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, null)
        },
        trailingContent = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
