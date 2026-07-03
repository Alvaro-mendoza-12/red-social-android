package conectate.com.presentation.profile

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.ui.platform.LocalContext
import conectate.com.data.repository.WebLoginManagedSession
import conectate.com.presentation.auth.AuthViewModel
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
    val webSessions by viewModel.webSessions.collectAsState()
    val webSessionsLoading by viewModel.webSessionsLoading.collectAsState()
    val webSessionMessage by viewModel.webSessionMessage.collectAsState()
    val storedAccounts by authViewModel.storedAccounts.collectAsState()
    val accountSwitchMessage by authViewModel.accountSwitchMessage.collectAsState()
    
    // Local state for immediate UI feedback, synced with DB
    var isPrivate by remember { mutableStateOf(false) }
    var visibility by remember { mutableStateOf("everyone") }
    var initialized by remember { mutableStateOf(false) }
    var newAdditionalEmail by remember { mutableStateOf("") }
    var showCloseAllWebSessionsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(settingsState) {
        if (!initialized && settingsState is SettingsUiState.Success) {
            val user = (settingsState as SettingsUiState.Success).user
            isPrivate = user.is_private
            visibility = user.profile_visibility
            initialized = true
        }
    }

    LaunchedEffect(accountSwitchMessage) {
        if (!accountSwitchMessage.isNullOrBlank()) {
            kotlinx.coroutines.delay(2500)
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

            SettingsSection(title = "Dispositivos vinculados") {
                OutlinedButton(
                    onClick = {
                        mainNavController.navigate(Screen.WebLoginScanner.createRoute()) {
                            launchSingleTop = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Vincular Conectate Web por QR")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Escanea el QR que aparece en la web y aprueba el inicio desde esta cuenta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                WebSessionsPanel(
                    sessions = webSessions,
                    isLoading = webSessionsLoading,
                    message = webSessionMessage,
                    onRefresh = viewModel::loadWebSessions,
                    onRevokeSession = viewModel::revokeWebSession,
                    onRevokeAll = { showCloseAllWebSessionsDialog = true }
                )
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
                                    }) {
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
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Divider()

            // Section: Admin Panel (solo visible para admins)
            if (viewModel.isAdmin) {
                SettingsSection(title = "Administración") {
                    Button(
                        onClick = { navController.navigate(Screen.AdminPanel.route) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Panel de Administrador")
                    }
                }

                Divider()
            }

            // Section: General
            SettingsSection(title = "General") {
                val scope = rememberCoroutineScope()
                
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
                
                Spacer(modifier = Modifier.height(16.dp))
                
            }
        }
    }

    if (showCloseAllWebSessionsDialog) {
        AlertDialog(
            onDismissRequest = { showCloseAllWebSessionsDialog = false },
            title = { Text("Cerrar sesiones web") },
            text = {
                Text("Se cerrarán todas las sesiones activas de Conectate Web vinculadas a tu cuenta.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCloseAllWebSessionsDialog = false
                        viewModel.revokeAllWebSessions()
                    }
                ) {
                    Text("Cerrar todas", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseAllWebSessionsDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun WebSessionsPanel(
    sessions: List<WebLoginManagedSession>,
    isLoading: Boolean,
    message: String?,
    onRefresh: () -> Unit,
    onRevokeSession: (String) -> Unit,
    onRevokeAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Sesiones web activas", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Cierra desde tu móvil cualquier navegador que ya no uses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        IconButton(onClick = onRefresh, enabled = !isLoading) {
            Icon(Icons.Default.Refresh, contentDescription = "Actualizar sesiones web")
        }
    }

    if (isLoading) {
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    if (!message.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    if (sessions.isEmpty() && !isLoading) {
        Text(
            "No hay sesiones web activas registradas en Ubuntu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    } else {
        sessions.forEach { session ->
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Computer, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.deviceLabel?.takeIf { it.isNotBlank() } ?: "Conectate Web",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        Text(
                            text = "Última actividad: ${formatWebSessionTime(session.lastSeenAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        session.ipAddress?.takeIf { it.isNotBlank() }?.let { ip ->
                            Text(
                                text = "IP: $ip",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                    IconButton(
                        onClick = { onRevokeSession(session.sessionId) },
                        enabled = !isLoading
                    ) {
                        Icon(
                            Icons.Default.Logout,
                            contentDescription = "Cerrar esta sesión web",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onRevokeAll,
            enabled = sessions.isNotEmpty() && !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Logout, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Cerrar todas las sesiones web")
        }
    }
}

private fun formatWebSessionTime(value: String?): String {
    val clean = value?.trim().orEmpty()
    if (clean.isBlank()) return "sin datos"
    return clean
        .replace("T", " ")
        .replace(Regex("\\.\\d+Z$"), " UTC")
        .replace(Regex("Z$"), " UTC")
        .take(24)
}

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
