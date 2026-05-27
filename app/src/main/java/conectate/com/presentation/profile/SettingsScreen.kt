package conectate.com.presentation.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.ui.platform.LocalContext
import conectate.com.data.model.User
import conectate.com.data.supabase.isSupabaseServiceRestrictionMessage
import conectate.com.presentation.auth.accountSwitchMessageAutoClearDelayMs
import conectate.com.presentation.auth.AuthViewModel
import conectate.com.presentation.auth.isPositiveAccountSwitchMessage
import conectate.com.presentation.navigation.Screen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    mainNavController: NavController,
    authViewModel: AuthViewModel,
    viewModel: SettingsViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    val settingsState by viewModel.settingsState.collectAsState()
    val additionalEmails by viewModel.additionalLoginEmails.collectAsState()
    val emailSettingsMessage by viewModel.emailSettingsMessage.collectAsState()
    val privacyAccepted by viewModel.privacyAccepted.collectAsState()
    val privacyMessage by viewModel.privacyMessage.collectAsState()
    val accountActionMessage by viewModel.accountActionMessage.collectAsState()
    val isDeletingAccount by viewModel.isDeletingAccount.collectAsState()
    val storedAccounts by authViewModel.storedAccounts.collectAsState()
    val accountSwitchMessage by authViewModel.accountSwitchMessage.collectAsState()
    val accountQuickAccessRestricted = isSupabaseServiceRestrictionMessage(accountSwitchMessage)
    val currentUser = (settingsState as? SettingsUiState.Success)?.user
    
    // Local state for immediate UI feedback, synced with DB
    var isPrivate by remember { mutableStateOf(false) }
    var visibility by remember { mutableStateOf("everyone") }
    var initialized by remember { mutableStateOf(false) }
    var newAdditionalEmail by remember { mutableStateOf("") }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }

    LaunchedEffect(settingsState) {
        if (!initialized && settingsState is SettingsUiState.Success) {
            val user = (settingsState as SettingsUiState.Success).user
            isPrivate = user.is_private
            visibility = user.profile_visibility
            initialized = true
        }
    }

    LaunchedEffect(accountSwitchMessage) {
        val autoClearDelayMs = accountSwitchMessageAutoClearDelayMs(accountSwitchMessage)
        if (autoClearDelayMs != null) {
            kotlinx.coroutines.delay(autoClearDelayMs)
            authViewModel.clearAccountSwitchMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            currentUser?.let { user ->
                ProfileControlCenterCard(
                    user = user,
                    onEditProfile = { navController.navigate(Screen.EditProfile.route) },
                    onAccount = { navController.navigate(Screen.AccountSettings.route) },
                    onSecurity = { navController.navigate(Screen.SecuritySettings.route) },
                    onPrivacy = { navController.navigate(Screen.PrivacySettings.route) },
                    onNotifications = { navController.navigate(Screen.NotificationSettings.route) },
                    onStorage = { navController.navigate(Screen.StorageSettings.route) },
                    onAppearance = { navController.navigate(Screen.ThemeSettings.route) },
                    onLanguage = { navController.navigate(Screen.LanguageSettings.route) },
                    onHelp = { navController.navigate(Screen.HelpSettings.route) },
                    onAbout = { navController.navigate(Screen.AboutSettings.route) }
                )
                Divider()
            }

            // Section: Cuenta
            SettingsSection(title = "Privacidad de la Cuenta") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cuenta Privada", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Solo tus seguidores podrán ver tus fotos y videos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = isPrivate,
                        onCheckedChange = { 
                            isPrivate = it
                            viewModel.updatePrivacy(isPrivate = it)
                        }
                    )
                }
            }
            
            Divider()

            // Section: Visibilidad
            SettingsSection(title = "Visibilidad del Perfil") {
                Text("¿Quién puede ver mi perfil?", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = visibility == "everyone",
                        onClick = { 
                            visibility = "everyone"
                            viewModel.updateVisibility("everyone")
                        }
                    )
                    Text("Todos")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = visibility == "connections",
                        onClick = { 
                            visibility = "connections"
                            viewModel.updateVisibility("connections")
                        }
                    )
                    Text("Solo Conexiones") // o "Amigos"
                }
            }

            Divider()

            // Section: Login Emails
            SettingsSection(title = "Correos de Inicio de Sesión") {
                val primaryEmail = (settingsState as? SettingsUiState.Success)?.user?.email ?: ""
                Text(
                    text = "Correo principal: $primaryEmail",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Puedes agregar hasta 3 correos adicionales para iniciar sesión. El principal no se puede eliminar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newAdditionalEmail,
                        onValueChange = { newAdditionalEmail = it.trim() },
                        label = { Text("Correo adicional") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.addAdditionalLoginEmail(newAdditionalEmail)
                            newAdditionalEmail = ""
                        },
                        enabled = additionalEmails.size < 3 && newAdditionalEmail.isNotBlank()
                    ) {
                        Text("Agregar")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (additionalEmails.isEmpty()) {
                    Text(
                        text = "Sin correos adicionales.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else {
                    additionalEmails.forEach { email ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = email,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.removeAdditionalLoginEmail(email) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Eliminar correo")
                            }
                        }
                    }
                }

                if (!emailSettingsMessage.isNullOrBlank()) {
                    Text(
                        text = emailSettingsMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            Divider()

            Divider()
            
            // Section: Pantalla
            SettingsSection(title = "Pantalla") {
                val isDarkMode by viewModel.isDarkMode.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Modo Oscuro", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = { viewModel.setDarkMode(it) }
                    )
                }
            }

            Divider()

            SettingsSection(title = "Privacidad y legales") {
                Button(
                    onClick = { mainNavController.navigate(Screen.PrivacyPolicy.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Políticas y normas de la comunidad")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { viewModel.acceptPrivacyPolicy() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !privacyAccepted
                ) {
                    Text(if (privacyAccepted) "Políticas ya aceptadas" else "Aceptar políticas de privacidad")
                }

                if (!privacyMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = privacyMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Divider()

            // Section: Soporte y Ayuda
            SettingsSection(title = "Soporte y Ayuda") {
                var showSupportDialog by remember { mutableStateOf(false) }
                var dialogStep by remember { mutableStateOf(1) } // 1: Warning, 2: Admin Selection

                Button(
                    onClick = { 
                        showSupportDialog = true 
                        dialogStep = 1
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Contactar Soporte")
                }

                if (showSupportDialog) {
                    AlertDialog(
                        onDismissRequest = { showSupportDialog = false },
                        title = { 
                            Text(if (dialogStep == 1) "¡Atención!" else "Seleccionar Administrador") 
                        },
                        text = {
                            if (dialogStep == 1) {
                                Text("Comuníquese con el soporte técnico únicamente para reportar problemas, quejas, errores de la aplicación u otros incidentes importantes.")
                            } else {
                                Text("¿Con quién desea hablar?")
                            }
                        },
                        confirmButton = {
                            if (dialogStep == 1) {
                                Button(onClick = { dialogStep = 2 }) {
                                    Text("Entendido")
                                }
                            } else {
                                    val context = LocalContext.current
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                        onClick = { 
                                            showSupportDialog = false
                                            try {
                                                viewModel.contactSupport("conectate", mainNavController, context)
                                            } catch (e: Exception) {
                                                android.util.Log.e("SettingsScreen", "Error contacting support", e)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Conectate (Admin)")
                                    }
                                    Button(
                                        onClick = { 
                                            showSupportDialog = false
                                            try {
                                                viewModel.contactSupport("alvaro", mainNavController, context)
                                            } catch (e: Exception) {
                                                android.util.Log.e("SettingsScreen", "Error contacting support", e)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Alvaro (Admin)")
                                    }
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSupportDialog = false }) {
                                Text("Cancelar")
                            }
                        }
                    )
                }
            }

            Divider()

            SettingsSection(title = "Monetización") {
                Button(
                    onClick = { navController.navigate(Screen.MonetizationCenter.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Premium, anuncios y wallet")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { navController.navigate(Screen.CreatorProgram.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Programa de creadores y pagos")
                }
            }

            Divider()

            SettingsSection(title = "Ajustes avanzados") {
                Button(
                    onClick = { navController.navigate(Screen.AccountSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cuenta")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate(Screen.SecuritySettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Seguridad")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate(Screen.PrivacySettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Privacidad")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate(Screen.NotificationSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Notificaciones")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate(Screen.ChatSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Chats")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate(Screen.StorageSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Almacenamiento y datos")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate(Screen.LanguageSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Idioma")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate(Screen.ThemeSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Tema y accesibilidad")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.navigate(Screen.HelpSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ayuda")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { navController.navigate(Screen.AboutSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Acerca de")
                }
            }

            Divider()

            SettingsSection(title = "Cambio rápido de cuenta") {
                val scope = rememberCoroutineScope()

                Button(
                    onClick = { authViewModel.saveCurrentAccount() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Guardar cuenta actual")
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (storedAccounts.isEmpty()) {
                    Text(
                        "No hay cuentas guardadas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else {
                    if (accountQuickAccessRestricted) {
                        Text(
                            text = "El cambio rápido de cuenta está pausado porque Supabase restringió temporalmente el proyecto.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    storedAccounts.forEach { account ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = account.display_name ?: account.email,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = account.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = {
                                        authViewModel.removeStoredAccount(account.id)
                                    }) {
                                        Text("Quitar")
                                    }
                                    TextButton(onClick = {
                                        authViewModel.switchAccount(account.id)
                                        scope.launch {
                                            kotlinx.coroutines.delay(1200)
                                            viewModel.refreshSettings()
                                        }
                                    }, enabled = !accountQuickAccessRestricted) {
                                        Text("Usar cuenta")
                                    }
                                }
                            }
                        }
                    }
                }

                if (!accountSwitchMessage.isNullOrBlank()) {
                    Text(
                        text = accountSwitchMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPositiveAccountSwitchMessage(accountSwitchMessage)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }

            Divider()

            // Section: General
            SettingsSection(title = "General") {
                val scope = rememberCoroutineScope()
                
                if (viewModel.isAdmin) {
                    Button(
                        onClick = { mainNavController.navigate(Screen.AdminPanel.route) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Settings, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Panel de Administración")
                    }
                }

                Button(
                    onClick = { 
                        scope.launch {
                            authViewModel.signOut()
                            onLogout()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cerrar Sesión")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showDeleteAccountDialog = true },
                    enabled = !isDeletingAccount,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isDeletingAccount) "Eliminando cuenta..." else "Eliminar cuenta")
                }

                if (!accountActionMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = accountActionMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (accountActionMessage?.contains("correctamente", ignoreCase = true) == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                
            }
        }
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = { Text("Eliminar cuenta") },
            text = {
                Text(
                    "Esta acción elimina tu usuario y sus datos asociados del backend. Guarda lo importante antes de continuar."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAccountDialog = false
                        viewModel.deleteOwnAccount(onDeleted = onLogout)
                    },
                    enabled = !isDeletingAccount
                ) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun ProfileControlCenterCard(
    user: User,
    onEditProfile: () -> Unit,
    onAccount: () -> Unit,
    onSecurity: () -> Unit,
    onPrivacy: () -> Unit,
    onNotifications: () -> Unit,
    onStorage: () -> Unit,
    onAppearance: () -> Unit,
    onLanguage: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit
) {
    val completion = remember(user) { profileCompletion(user) }
    val progress = completion.first.toFloat() / completion.second.toFloat()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Centro de perfil",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Banner, avatar, presencia, intereses y seguridad en un solo lugar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${completion.first}/${completion.second}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            LinearProgressIndicator(
                progress = progress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth()
            )

            ProfileSettingsShortcut(
                icon = Icons.Default.Edit,
                title = "Editar perfil completo",
                subtitle = "Banner, avatar, bio, intereses, juegos, música, carrera y horario.",
                onClick = onEditProfile
            )
            ProfileSettingsShortcut(
                icon = Icons.Default.AccountCircle,
                title = "Cuenta y correos",
                subtitle = "Email principal, correos de acceso y cambio rápido de cuenta.",
                onClick = onAccount
            )
            ProfileSettingsShortcut(
                icon = Icons.Default.Security,
                title = "Seguridad",
                subtitle = "Contraseña y autenticación en dos pasos.",
                onClick = onSecurity
            )
            ProfileSettingsShortcut(
                icon = Icons.Default.Lock,
                title = "Privacidad y presencia",
                subtitle = "Perfil, último acceso y estado en línea.",
                onClick = onPrivacy
            )
            ProfileSettingsShortcut(
                icon = Icons.Default.Notifications,
                title = "Notificaciones",
                subtitle = "Mensajes, llamadas, social y lives.",
                onClick = onNotifications
            )
            ProfileSettingsShortcut(
                icon = Icons.Default.Storage,
                title = "Almacenamiento y datos",
                subtitle = "Caché, descarga multimedia, red y ahorro de datos.",
                onClick = onStorage
            )
            ProfileSettingsShortcut(
                icon = Icons.Default.Palette,
                title = "Apariencia y accesibilidad",
                subtitle = "Tema, fuente, escala de texto y movimiento reducido.",
                onClick = onAppearance
            )
            ProfileSettingsShortcut(
                icon = Icons.Default.Language,
                title = "Idioma",
                subtitle = "Preferencia de idioma de la aplicación.",
                onClick = onLanguage
            )
            ProfileSettingsShortcut(
                icon = Icons.Default.Help,
                title = "Ayuda y soporte",
                subtitle = "Soporte, políticas y normas de comunidad.",
                onClick = onHelp
            )
            ProfileSettingsShortcut(
                icon = Icons.Default.Info,
                title = "Sobre Conectate",
                subtitle = "Versión, privacidad y detalles del producto.",
                onClick = onAbout
            )
        }
    }
}

@Composable
private fun ProfileSettingsShortcut(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = null)
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun profileCompletion(user: User): Pair<Int, Int> {
    val completed = listOf(
        user.avatar_url.hasContent(),
        user.cover_url.hasContent(),
        user.bio.hasContent(),
        user.status.hasContent(),
        user.interests.isNotEmpty(),
        user.linked_games.isNotEmpty(),
        user.favorite_music_url.hasContent(),
        user.favorite_book.hasContent(),
        user.favorite_sport.hasContent(),
        user.career.hasContent(),
        user.availability_schedule.hasContent()
    ).count { it }

    return completed to 11
}

private fun String?.hasContent(): Boolean =
    !this.isNullOrBlank() && !this.equals("null", ignoreCase = true)

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        content()
    }
}
