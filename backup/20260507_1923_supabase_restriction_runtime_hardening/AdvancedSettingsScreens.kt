package conectate.com.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.BuildConfig
import conectate.com.data.local.prefs.AppFontOption
import conectate.com.presentation.auth.AuthViewModel
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.profile.SettingsUiState
import conectate.com.presentation.profile.SettingsViewModel
import conectate.com.util.Constants

@Composable
fun AccountSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val settingsState by settingsViewModel.settingsState.collectAsState()
    val additionalEmails by settingsViewModel.additionalLoginEmails.collectAsState()
    val emailSettingsMessage by settingsViewModel.emailSettingsMessage.collectAsState()
    val storedAccounts by authViewModel.storedAccounts.collectAsStateWithLifecycle()
    val accountSwitchMessage by authViewModel.accountSwitchMessage.collectAsStateWithLifecycle()
    var newAdditionalEmail by rememberSaveable { mutableStateOf("") }

    SettingsScaffold(
        navController = navController,
        title = "Cuenta"
    ) {
        val user = (settingsState as? SettingsUiState.Success)?.user

        SettingsSectionCard(title = "Cuenta principal") {
            Text(
                text = user?.email ?: "Cargando cuenta…",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { authViewModel.saveCurrentAccount() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Guardar cuenta actual")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { navController.navigate(Screen.AddAccountLogin.route) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Agregar otra cuenta")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { navController.navigate(Screen.ChangePassword.route) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cambiar contraseña")
            }
        }

        SettingsSectionCard(title = "Correos de acceso") {
            Text(
                text = "Puedes guardar hasta 3 correos adicionales para iniciar sesión con la misma cuenta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = newAdditionalEmail,
                onValueChange = { newAdditionalEmail = it.trim() },
                label = { Text("Correo adicional") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    settingsViewModel.addAdditionalLoginEmail(newAdditionalEmail)
                    newAdditionalEmail = ""
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = newAdditionalEmail.isNotBlank() && additionalEmails.size < 3
            ) {
                Text("Agregar correo")
            }
            if (additionalEmails.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sin correos adicionales guardados.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                additionalEmails.forEach { email ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = email,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { settingsViewModel.removeAdditionalLoginEmail(email) }) {
                            Text("Eliminar")
                        }
                    }
                }
            }
            if (!emailSettingsMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = emailSettingsMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        SettingsSectionCard(title = "Cambio rápido de cuenta") {
            if (storedAccounts.isEmpty()) {
                Text(
                    text = "Todavía no hay cuentas guardadas en este dispositivo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                storedAccounts.forEach { account ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = account.display_name ?: account.email,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = account.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { authViewModel.removeStoredAccount(account.id) }) {
                                    Text("Quitar")
                                }
                                TextButton(onClick = { authViewModel.switchAccount(account.id) }) {
                                    Text("Usar cuenta")
                                }
                            }
                        }
                    }
                }
            }
            if (!accountSwitchMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = accountSwitchMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun SecuritySettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val twoFactorStatus by viewModel.twoFactorStatus.collectAsState()
    val setupState by viewModel.twoFactorSetup.collectAsState()
    val clipboard = LocalClipboardManager.current
    var verificationCode by rememberSaveable { mutableStateOf("") }
    var showDisableDialog by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        navController = navController,
        title = "Seguridad"
    ) {
        SettingsSectionCard(title = "Acceso") {
            SettingsActionRow(
                title = "Cambiar contraseña",
                subtitle = "Abre el flujo seguro de recuperación o cambio de clave.",
                onClick = { navController.navigate(Screen.ChangePassword.route) }
            )
        }

        SettingsSectionCard(title = "Autenticación en dos pasos") {
            Text(
                text = if (twoFactorStatus.isEnabled) {
                    "2FA activada. Códigos de respaldo disponibles: ${twoFactorStatus.unusedBackupCodesCount}."
                } else {
                    "Protege tu cuenta con códigos TOTP y códigos de respaldo."
                },
                style = MaterialTheme.typography.bodyMedium
            )

            if (twoFactorStatus.isLockedOut) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Bloqueo temporal activo. Intenta de nuevo en ${twoFactorStatus.remainingLockoutTime / 1000}s.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!twoFactorStatus.isEnabled && setupState.secret == null && !setupState.isLoading) {
                Button(
                    onClick = { viewModel.startTwoFactorSetup() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Configurar 2FA")
                }
            }

            if (setupState.isLoading) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Procesando configuración segura…",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!setupState.secret.isNullOrBlank() && !twoFactorStatus.isEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                SelectionBlock(
                    label = "Clave secreta",
                    value = setupState.secret.orEmpty(),
                    onCopy = {
                        clipboard.setText(AnnotatedString(setupState.secret.orEmpty()))
                    }
                )
                if (!setupState.otpauthUrl.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    SelectionBlock(
                        label = "Enlace otpauth",
                        value = setupState.otpauthUrl.orEmpty(),
                        onCopy = {
                            clipboard.setText(AnnotatedString(setupState.otpauthUrl.orEmpty()))
                        }
                    )
                }
                if (setupState.backupCodes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    SelectionBlock(
                        label = "Códigos de respaldo",
                        value = setupState.backupCodes.joinToString("\n"),
                        onCopy = {
                            clipboard.setText(AnnotatedString(setupState.backupCodes.joinToString("\n")))
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = verificationCode,
                    onValueChange = { verificationCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("Código de verificación") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.confirmTwoFactorSetup(verificationCode) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = verificationCode.length == 6 && !setupState.isLoading
                ) {
                    Text("Verificar y activar 2FA")
                }
            }

            if (twoFactorStatus.isEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDisableDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Desactivar 2FA")
                }
            }

            if (!setupState.message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = setupState.message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (twoFactorStatus.isEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }

    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDialog = false },
            title = { Text("Desactivar 2FA") },
            text = { Text("Esto quitará el segundo factor de autenticación de la cuenta actual.") },
            confirmButton = {
                TextButton(onClick = {
                    showDisableDialog = false
                    verificationCode = ""
                    viewModel.disableTwoFactor()
                }) {
                    Text("Desactivar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun PrivacySettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settingsState by viewModel.settingsState.collectAsState()
    val privacyMessage by viewModel.privacyMessage.collectAsState()
    val user = (settingsState as? SettingsUiState.Success)?.user

    var isPrivate by rememberSaveable { mutableStateOf(false) }
    var profileVisibility by rememberSaveable { mutableStateOf("everyone") }
    var lastSeenVisibility by rememberSaveable { mutableStateOf("everyone") }
    var onlineVisibility by rememberSaveable { mutableStateOf("everyone") }

    LaunchedEffect(user?.id) {
        if (user != null) {
            isPrivate = user.is_private
            profileVisibility = user.profile_visibility
            lastSeenVisibility = user.last_seen_visibility
            onlineVisibility = user.online_status_visibility
        }
    }

    SettingsScaffold(
        navController = navController,
        title = "Privacidad"
    ) {
        SettingsSectionCard(title = "Visibilidad") {
            ToggleRow(
                title = "Cuenta privada",
                subtitle = "Solo seguidores aprobados verán el contenido privado.",
                checked = isPrivate,
                onCheckedChange = {
                    isPrivate = it
                    viewModel.updatePrivacy(it)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Quién puede ver tu perfil",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            VisibilitySelector(
                selected = profileVisibility,
                options = listOf("everyone" to "Todos", "connections" to "Solo conexiones"),
                onSelected = {
                    profileVisibility = it
                    viewModel.updateVisibility(it)
                }
            )
        }

        SettingsSectionCard(title = "Presencia") {
            Text(
                text = "Última vez en línea",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            VisibilitySelector(
                selected = lastSeenVisibility,
                options = listOf(
                    "everyone" to "Todos",
                    "connections" to "Solo conexiones",
                    "nobody" to "Nadie"
                ),
                onSelected = {
                    lastSeenVisibility = it
                    viewModel.updatePresenceVisibility(lastSeenVisibility = it)
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Estado en línea",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            VisibilitySelector(
                selected = onlineVisibility,
                options = listOf(
                    "everyone" to "Todos",
                    "connections" to "Solo conexiones",
                    "nobody" to "Nadie"
                ),
                onSelected = {
                    onlineVisibility = it
                    viewModel.updatePresenceVisibility(onlineStatusVisibility = it)
                }
            )
            if (!privacyMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = privacyMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun NotificationSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val messageNotificationsEnabled by viewModel.messageNotificationsEnabled.collectAsState()
    val callNotificationsEnabled by viewModel.callNotificationsEnabled.collectAsState()
    val socialNotificationsEnabled by viewModel.socialNotificationsEnabled.collectAsState()
    val liveNotificationsEnabled by viewModel.liveNotificationsEnabled.collectAsState()
    val chatSoundsEnabled by viewModel.chatSoundsEnabled.collectAsState()

    SettingsScaffold(
        navController = navController,
        title = "Notificaciones"
    ) {
        SettingsSectionCard(title = "Push y actividad") {
            ToggleRow(
                title = "Mensajes",
                subtitle = "Avisos de chats directos, grupos y comunidades.",
                checked = messageNotificationsEnabled,
                onCheckedChange = viewModel::setMessageNotificationsEnabled
            )
            ToggleRow(
                title = "Llamadas",
                subtitle = "Entrantes 1:1, voz y video.",
                checked = callNotificationsEnabled,
                onCheckedChange = viewModel::setCallNotificationsEnabled
            )
            ToggleRow(
                title = "Social",
                subtitle = "Likes, comentarios, menciones y solicitudes.",
                checked = socialNotificationsEnabled,
                onCheckedChange = viewModel::setSocialNotificationsEnabled
            )
            ToggleRow(
                title = "Lives",
                subtitle = "Directos, gifts y alertas de creadores.",
                checked = liveNotificationsEnabled,
                onCheckedChange = viewModel::setLiveNotificationsEnabled
            )
            ToggleRow(
                title = "Sonidos del chat",
                subtitle = "Reproduce sonidos locales al enviar y recibir.",
                checked = chatSoundsEnabled,
                onCheckedChange = viewModel::setChatSoundsEnabled
            )
        }
    }
}

@Composable
fun StorageSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val dataSaverEnabled by viewModel.dataSaverEnabled.collectAsState()
    val autoDownloadOnWifi by viewModel.autoDownloadOnWifi.collectAsState()
    val autoDownloadOnMobile by viewModel.autoDownloadOnMobile.collectAsState()
    val mediaAutoDownloadEnabled by viewModel.mediaAutoDownloadEnabled.collectAsState()
    val connectivityHintsEnabled by viewModel.connectivityHintsEnabled.collectAsState()

    SettingsScaffold(
        navController = navController,
        title = "Almacenamiento y datos"
    ) {
        SettingsSectionCard(title = "Descargas") {
            ToggleRow(
                title = "Descarga automática general",
                subtitle = "Permite predescargar multimedia del chat cuando esté disponible.",
                checked = mediaAutoDownloadEnabled,
                onCheckedChange = viewModel::setMediaAutoDownloadEnabled
            )
            ToggleRow(
                title = "Solo Wi‑Fi",
                subtitle = "Prioriza Wi‑Fi para descargas pesadas.",
                checked = autoDownloadOnWifi,
                onCheckedChange = viewModel::setAutoDownloadOnWifi
            )
            ToggleRow(
                title = "Permitir en datos móviles",
                subtitle = "Úsalo solo si tu plan soporta tráfico multimedia.",
                checked = autoDownloadOnMobile,
                onCheckedChange = viewModel::setAutoDownloadOnMobile
            )
        }

        SettingsSectionCard(title = "Rendimiento") {
            ToggleRow(
                title = "Ahorro de datos",
                subtitle = "Reduce precargas, sincronización agresiva y tráfico innecesario.",
                checked = dataSaverEnabled,
                onCheckedChange = viewModel::setDataSaverEnabled
            )
            ToggleRow(
                title = "Avisos de conectividad",
                subtitle = "Muestra pistas cuando una conversación pierde sincronización.",
                checked = connectivityHintsEnabled,
                onCheckedChange = viewModel::setConnectivityHintsEnabled
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (Constants.STORAGE_ENABLED) {
                    "El almacenamiento multimedia está activo en esta build."
                } else {
                    "El almacenamiento multimedia externo está desactivado en esta build."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LanguageSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val language by viewModel.language.collectAsState()

    SettingsScaffold(
        navController = navController,
        title = "Idioma"
    ) {
        SettingsSectionCard(title = "Idioma de la aplicación") {
            Text(
                text = "La selección queda guardada para futuras integraciones de localización.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            listOf(
                "system" to "Usar idioma del dispositivo",
                "es" to "Español",
                "en" to "English"
            ).forEach { (value, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = language == value,
                            onClick = { viewModel.setLanguage(value) }
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = language == value,
                        onClick = { viewModel.setLanguage(value) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label)
                }
            }
        }
    }
}

@Composable
fun ThemeSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val fontOption by viewModel.fontOption.collectAsState()
    val textScale by viewModel.textScale.collectAsState()
    val reduceMotionEnabled by viewModel.reduceMotionEnabled.collectAsState()

    SettingsScaffold(
        navController = navController,
        title = "Tema y accesibilidad"
    ) {
        SettingsSectionCard(title = "Apariencia") {
            ToggleRow(
                title = "Modo oscuro",
                subtitle = "Alterna entre apariencia clara y oscura.",
                checked = isDarkMode,
                onCheckedChange = viewModel::setDarkMode
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Escala del texto: ${"%.2f".format(textScale)}x",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = textScale,
                onValueChange = viewModel::setTextScale,
                valueRange = 0.9f..1.25f
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Fuente",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowChips(
                options = AppFontOption.entries.map { it.storageValue to it.label },
                selected = fontOption.storageValue,
                onSelected = { raw ->
                    viewModel.setFontOption(AppFontOption.fromStorage(raw))
                }
            )
        }

        SettingsSectionCard(title = "Accesibilidad") {
            ToggleRow(
                title = "Reducir movimiento",
                subtitle = "Guarda la preferencia para animaciones más suaves.",
                checked = reduceMotionEnabled,
                onCheckedChange = viewModel::setReduceMotionEnabled
            )
            ToggleRow(
                title = "Modo compacto",
                subtitle = "Reduce espacios y densidad visual en listas de mensajes.",
                checked = viewModel.compactMode.collectAsState().value,
                onCheckedChange = viewModel::setCompactMode
            )
        }
    }
}

@Composable
fun HelpSettingsScreen(
    navController: NavController,
    rootNavController: NavController = navController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    SettingsScaffold(
        navController = navController,
        title = "Ayuda"
    ) {
        SettingsSectionCard(title = "Centro de ayuda") {
            SettingsActionRow(
                title = "Políticas y normas",
                subtitle = "Revisa privacidad, comunidad y lineamientos de uso.",
                onClick = { navController.navigate(Screen.PrivacyPolicy.route) }
            )
            SettingsActionRow(
                title = "Contactar soporte Conectate",
                subtitle = "Abre el chat directo con el administrador principal.",
                onClick = { viewModel.contactSupport("conectate", rootNavController, context) }
            )
            SettingsActionRow(
                title = "Contactar soporte Alvaro",
                subtitle = "Canal alterno para incidencias o revisión manual.",
                onClick = { viewModel.contactSupport("alvaro", rootNavController, context) }
            )
        }
    }
}

@Composable
fun AboutSettingsScreen(
    navController: NavController
) {
    SettingsScaffold(
        navController = navController,
        title = "Acerca de"
    ) {
        SettingsSectionCard(title = "Aplicación") {
            Text(
                text = "Conectate",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Versión ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Build enfocada en mensajería, social, video corto, lives y multi‑cuenta.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            SettingsActionRow(
                title = "Privacidad y términos",
                subtitle = "Abre la política vigente y normas de la comunidad.",
                onClick = { navController.navigate(Screen.PrivacyPolicy.route) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    navController: NavController,
    title: String,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
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
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = false, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun VisibilitySelector(
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    Spacer(modifier = Modifier.height(8.dp))
    FlowChips(
        options = options,
        selected = selected,
        onSelected = onSelected
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.chunked(2).forEach { rowOptions ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowOptions.forEach { (value, label) ->
                    FilterChip(
                        selected = selected == value,
                        onClick = { onSelected(value) },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBlock(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copiar")
                }
            }
            Divider()
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
