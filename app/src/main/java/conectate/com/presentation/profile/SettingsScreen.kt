package conectate.com.presentation.profile

import conectate.com.BuildConfig
import androidx.compose.runtime.Composable
import android.content.Intent
import android.provider.Settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.AccountCircle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import conectate.com.data.repository.WebLoginManagedSession
import conectate.com.presentation.auth.AuthViewModel
import conectate.com.presentation.navigation.Screen

private const val NOVVIN_WEB_URL = "https://novvinapp.com"

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
    val adminPanelAccess by viewModel.adminPanelAccess.collectAsState()
    val isDeletingAccount by viewModel.isDeletingAccount.collectAsState()
    val deleteAccountMessage by viewModel.deleteAccountMessage.collectAsState()
    val accountDeleted by viewModel.accountDeleted.collectAsState()
    val storedAccounts by authViewModel.storedAccounts.collectAsState()
    val accountSwitchMessage by authViewModel.accountSwitchMessage.collectAsState()
    val currentUserId by authViewModel.currentUserId.collectAsState()
    val twoFactorStatus by viewModel.serverTwoFactorStatus.collectAsState()
    val twoFactorSetup by viewModel.serverTwoFactorSetup.collectAsState()
    val recoveryCodes by viewModel.serverRecoveryCodes.collectAsState()
    val twoFactorBusy by viewModel.twoFactorBusy.collectAsState()
    val twoFactorMessage by viewModel.twoFactorMessage.collectAsState()
    val twoFactorDisabled by viewModel.twoFactorDisabled.collectAsState()
    val passwordRecoveryRemaining by viewModel.passwordRecoveryRemaining.collectAsState()
    val passwordRecoveryCodes by viewModel.passwordRecoveryCodes.collectAsState()
    val passwordRecoveryBusy by viewModel.passwordRecoveryBusy.collectAsState()
    val passwordRecoveryMessage by viewModel.passwordRecoveryMessage.collectAsState()
    var twoFactorExpanded by rememberSaveable { mutableStateOf(false) }
    var twoFactorPassword by remember { mutableStateOf("") }
    var twoFactorCode by remember { mutableStateOf("") }
    var passwordRecoveryExpanded by rememberSaveable { mutableStateOf(false) }
    var passwordRecoveryPassword by remember { mutableStateOf("") }
    var passwordRecoveryFactor by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    val screenContext = LocalContext.current
    val settingsLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(settingsLifecycleOwner, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.loadWebSessions()
        }
        settingsLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { settingsLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(twoFactorDisabled) {
        if (twoFactorDisabled) onLogout()
    }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val accountEmail = (settingsState as? SettingsUiState.Success)?.user?.email
            viewModel.exportSettingsBackup(uri, accountEmail).fold(
                onSuccess = {
                    android.widget.Toast.makeText(screenContext, "Copia de preferencias guardada.", android.widget.Toast.LENGTH_SHORT).show()
                },
                onFailure = {
                    android.widget.Toast.makeText(screenContext, it.message ?: "No se pudo crear la copia.", android.widget.Toast.LENGTH_LONG).show()
                },
            )
        }
    }
    val importBackupLauncher = conectate.com.presentation.documents.rememberInAppDocumentPicker() { uri ->
        if (uri != null) {
            viewModel.importSettingsBackup(uri).fold(
                onSuccess = {
                    android.widget.Toast.makeText(screenContext, "Preferencias restauradas.", android.widget.Toast.LENGTH_SHORT).show()
                },
                onFailure = {
                    android.widget.Toast.makeText(screenContext, it.message ?: "No se pudo restaurar la copia.", android.widget.Toast.LENGTH_LONG).show()
                },
            )
        }
    }
    
    // Local state for immediate UI feedback, synced with DB
    var isPrivate by remember { mutableStateOf(false) }
    var favoritesPublic by remember { mutableStateOf(false) }
    var profileViewHistoryEnabled by remember { mutableStateOf(true) }
    var breakMode by remember { mutableStateOf("off") }
    var visibility by remember { mutableStateOf("everyone") }
    var initialized by remember { mutableStateOf(false) }
    var newAdditionalEmail by remember { mutableStateOf("") }
    var showCloseAllWebSessionsDialog by remember { mutableStateOf(false) }
    var showBreakModeDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var adminNavigationError by remember { mutableStateOf<String?>(null) }
    var lastBackupRequest by remember { mutableStateOf(viewModel.lastAndroidBackupRequest()) }
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    var expandedSettingsSection by rememberSaveable { mutableStateOf<String?>("Actividad") }

    LaunchedEffect(accountDeleted) {
        if (accountDeleted) {
            viewModel.consumeAccountDeleted()
            authViewModel.forceLocalLogout()
            onLogout()
        }
    }

    LaunchedEffect(settingsState) {
        if (!initialized && settingsState is SettingsUiState.Success) {
            val user = (settingsState as SettingsUiState.Success).user
            isPrivate = user.is_private
            favoritesPublic = user.favorites_public
            profileViewHistoryEnabled = user.profile_view_history_enabled
            breakMode = user.break_mode
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
                title = { Text("Ajustes y privacidad", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        CompositionLocalProvider(LocalSettingsDirectory provides SettingsDirectoryState(
            query = settingsQuery,
            expanded = expandedSettingsSection,
            toggle = { title -> expandedSettingsSection = if (expandedSettingsSection == title) null else title },
        )) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = settingsQuery,
                onValueChange = { settingsQuery = it.take(100) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Buscar en ajustes") },
                placeholder = { Text("Privacidad, idioma, copia de seguridad…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (settingsQuery.isNotEmpty()) IconButton(onClick = { settingsQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Limpiar búsqueda")
                    }
                },
                singleLine = true,
            )
            Text(
                "Abre una categoría para ver sus opciones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settingsQuery.isNotBlank() && !SettingsDirectory.hasMatches(settingsQuery, adminPanelAccess)) {
                Text("No encontramos ese ajuste. Prueba con privacidad, idioma o cuenta.")
            }
            SettingsSection(title = "Actividad") {
                SettingsAreaButton(
                    icon = Icons.Default.Shield,
                    title = "Preferencias de contenido",
                    subtitle = "Contenido sensible, +18 y comentarios ofensivos",
                    onClick = { navController.navigate(Screen.ContentSafety.route) }
                )
                SettingsAreaButton(
                    icon = Icons.Default.Settings,
                    title = "Mi algoritmo",
                    subtitle = "Controla temas, creadores y la mezcla de Videos y Social",
                    onClick = { navController.navigate(Screen.SettingsCategory.createRoute("algorithm")) }
                )
                SettingsAreaButton(
                    icon = Icons.AutoMirrored.Filled.Message,
                    title = "Mensajes y llamadas",
                    subtitle = "Chats, sonidos, lectura, llamadas y notificaciones",
                    onClick = { navController.navigate(Screen.SettingsCategory.createRoute("messages")) }
                )
                SettingsAreaButton(
                    icon = Icons.Default.Movie,
                    title = "Videos y multimedia",
                    subtitle = "Datos, descargas y reproducción",
                    onClick = { navController.navigate(Screen.SettingsCategory.createRoute("videos")) }
                )
                SettingsAreaButton(
                    icon = Icons.Default.Groups,
                    title = "Social",
                    subtitle = "Radar, actividad, directos y comunidades",
                    onClick = { navController.navigate(Screen.SettingsCategory.createRoute("social")) }
                )
                SettingsAreaButton(
                    icon = Icons.Default.AccountBalanceWallet,
                    title = "Monedas y pagos",
                    subtitle = "Saldo, recargas, montos e historial",
                    onClick = { navController.navigate(Screen.CoinsAndPayments.route) }
                )
                SettingsAreaButton(
                    icon = Icons.Default.Person,
                    title = "Perfil y apariencia",
                    subtitle = "Datos, privacidad, tema y accesibilidad",
                    onClick = { navController.navigate(Screen.SettingsCategory.createRoute("profile")) }
                )
            }

            SettingsSection(title = "Centro de actividad") {
                SettingsAreaButton(
                    icon = Icons.Default.Analytics,
                    title = "Estadísticas del creador",
                    subtitle = "Audiencia, retención y rendimiento de videos y directos",
                    onClick = { navController.navigate(Screen.CreatorAnalytics.route) }
                )
                SettingsAreaButton(
                    icon = Icons.Default.Notifications,
                    title = "Actividad y menciones",
                    subtitle = "Reacciones, comentarios, menciones y novedades de tu cuenta",
                    onClick = { mainNavController.navigate(Screen.Notifications.route) }
                )
                if (!currentUserId.isNullOrBlank()) {
                    SettingsAreaButton(
                        icon = Icons.Default.Person,
                        title = "Personas que sigues",
                        subtitle = "Consulta y administra tu lista de seguidos",
                        onClick = {
                            mainNavController.navigate(Screen.FollowList.createRoute(currentUserId.orEmpty(), "following"))
                        }
                    )
                    SettingsAreaButton(
                        icon = Icons.Default.Groups,
                        title = "Tus seguidores",
                        subtitle = "Personas que siguen tu perfil",
                        onClick = {
                            mainNavController.navigate(Screen.FollowList.createRoute(currentUserId.orEmpty(), "followers"))
                        }
                    )
                }
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
                            val previous = isPrivate
                            isPrivate = it
                            viewModel.updatePrivacy(isPrivate = it) { saved ->
                                if (!saved) isPrivate = previous
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Favoritos públicos", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Permite que otros usuarios vean tus videos favoritos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (!initialized) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(
                            checked = favoritesPublic,
                            onCheckedChange = {
                                val previous = favoritesPublic
                                favoritesPublic = it
                                viewModel.updateFavoritesVisibility(it) { saved ->
                                    if (!saved) favoritesPublic = previous
                                }
                            }
                        )
                    }
                }
            }
            

            // Section: Visibilidad
            SettingsSection(title = "Visibilidad del Perfil") {
                Text("¿Quién puede ver mi perfil?", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = visibility == "everyone",
                        onClick = { 
                            val previous = visibility
                            visibility = "everyone"
                            viewModel.updateVisibility("everyone") { saved ->
                                if (!saved) visibility = previous
                            }
                        }
                    )
                    Text("Todos")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = visibility == "connections",
                        onClick = { 
                            val previous = visibility
                            visibility = "connections"
                            viewModel.updateVisibility("connections") { saved ->
                                if (!saved) visibility = previous
                            }
                        }
                    )
                    Text("Solo Conexiones") // o "Amigos"
                }
            }


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


            SettingsSection(title = "Cuenta y seguridad") {
                OutlinedButton(
                    onClick = { showDeleteAccountDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Solicitar eliminación de mi cuenta")
                }
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { navController.navigate(Screen.AccountSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Person, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Datos del perfil")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { mainNavController.navigate(Screen.ChangePassword.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Cambiar contraseña")
                }

                if (twoFactorStatus != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { twoFactorExpanded = !twoFactorExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(if (twoFactorStatus?.enabled == true)
                            "Verificación en dos pasos · Activada" else "Activar verificación en dos pasos")
                    }
                    if (twoFactorExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Tu autenticador protege los accesos nuevos. Guarda los códigos de respaldo fuera de este teléfono.")
                        OutlinedTextField(
                            value = twoFactorPassword,
                            onValueChange = { twoFactorPassword = it },
                            label = { Text("Contraseña actual") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        if (twoFactorSetup != null) {
                            Text("Clave para tu autenticador:")
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(twoFactorSetup?.secret.orEmpty())
                            }
                        }
                        if (twoFactorSetup != null || twoFactorStatus?.enabled == true) {
                            OutlinedTextField(
                                value = twoFactorCode,
                                onValueChange = { twoFactorCode = it.take(32) },
                                label = { Text(if (twoFactorStatus?.enabled == true)
                                    "Código de autenticador o respaldo" else "Código de 6 dígitos") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        when {
                            twoFactorStatus?.enabled == true -> {
                                Text("Códigos de respaldo disponibles: ${twoFactorStatus?.recoveryCodesRemaining ?: 0}")
                                Button(
                                    onClick = { viewModel.disableServerTwoFactor(twoFactorPassword, twoFactorCode) },
                                    enabled = !twoFactorBusy && twoFactorPassword.isNotBlank() && twoFactorCode.isNotBlank()
                                ) { Text("Desactivar y cerrar sesión") }
                            }
                            twoFactorSetup == null -> Button(
                                onClick = { viewModel.setupServerTwoFactor(twoFactorPassword) },
                                enabled = !twoFactorBusy && twoFactorPassword.isNotBlank()
                            ) { Text("Preparar autenticador") }
                            else -> Button(
                                onClick = { viewModel.enableServerTwoFactor(twoFactorPassword, twoFactorCode) },
                                enabled = !twoFactorBusy && twoFactorPassword.isNotBlank() &&
                                    twoFactorCode.matches(Regex("[0-9]{6}"))
                            ) { Text("Confirmar y activar") }
                        }
                        twoFactorMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        if (recoveryCodes.isNotEmpty()) {
                            Text("Guarda estos códigos de un solo uso. No volverán a mostrarse:")
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(recoveryCodes.joinToString("\n"))
                            }
                            TextButton(onClick = {
                                viewModel.clearDisplayedRecoveryCodes()
                                twoFactorPassword = ""
                                twoFactorCode = ""
                            }) { Text("Ya los guardé") }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { passwordRecoveryExpanded = !passwordRecoveryExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Códigos de respaldo (opcionales)")
                }
                if (passwordRecoveryExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Estos códigos son un atajo si los guardas antes, fuera del teléfono. No tienes que memorizarlos. Si pierdes la contraseña y no tienes uno, usa «Solicitar ayuda» para que un administrador verifique tu cuenta.")
                    passwordRecoveryRemaining?.let { Text("Códigos disponibles: $it") }
                    OutlinedTextField(
                        value = passwordRecoveryPassword,
                        onValueChange = { passwordRecoveryPassword = it },
                        label = { Text("Contraseña actual") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    if (twoFactorStatus?.enabled == true) {
                        OutlinedTextField(
                            value = passwordRecoveryFactor,
                            onValueChange = { passwordRecoveryFactor = it.take(32) },
                            label = { Text("Código de autenticador o respaldo") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.rotatePasswordRecoveryCodes(
                                passwordRecoveryPassword,
                                passwordRecoveryFactor.takeIf(String::isNotBlank)
                            )
                        },
                        enabled = !passwordRecoveryBusy && passwordRecoveryPassword.isNotBlank() &&
                            (twoFactorStatus?.enabled != true || passwordRecoveryFactor.isNotBlank()),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (passwordRecoveryRemaining == 0) "Crear códigos" else "Reemplazar códigos") }
                    passwordRecoveryMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (passwordRecoveryCodes.isNotEmpty()) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(passwordRecoveryCodes.joinToString("\n"))
                        }
                        TextButton(onClick = {
                            viewModel.clearDisplayedPasswordRecoveryCodes()
                            passwordRecoveryPassword = ""
                            passwordRecoveryFactor = ""
                        }) { Text("Ya los guardé") }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { navController.navigate(Screen.ChatSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Preferencias de chat")
                }
            }


            SettingsSection(title = "Ambiente") {
                FilledTonalButton(
                    onClick = { navController.navigate(Screen.CustomizeApp.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Palette, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Temas y perfil")
                }
            }

            SettingsSection(title = "Copia de seguridad") {
                val backupEmail = (settingsState as? SettingsUiState.Success)?.user?.email.orEmpty()
                val backupFileName = remember(backupEmail) {
                    val account = backupEmail.substringBefore('@').filter { it.isLetterOrDigit() || it in "-_" }.take(32)
                    if (account.isBlank()) "copia-novvin.novvinbackup" else "novvin-$account.novvinbackup"
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            Icons.Default.CloudSync,
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp).size(26.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Respaldo de Novvin", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(
                            lastBackupRequest?.let { "Última solicitud: ${formatBackupTime(it)}" }
                                ?: "Aún no has solicitado una copia",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SettingsAreaButton(
                    icon = Icons.Default.AccountCircle,
                    title = "Cuenta del respaldo",
                    subtitle = backupEmail.ifBlank { "Seleccionar desde Android" },
                    onClick = {
                        runCatching {
                            screenContext.startActivity(Intent("android.settings.BACKUP_SETTINGS"))
                        }.onFailure {
                            screenContext.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
                        }
                    },
                )
                Button(
                    onClick = {
                        viewModel.scheduleAndroidBackup(backupEmail).fold(
                            onSuccess = { requestedAt ->
                                lastBackupRequest = requestedAt
                                android.widget.Toast.makeText(
                                    screenContext,
                                    "Copia solicitada. Android la completará con conexión disponible.",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            },
                            onFailure = {
                                android.widget.Toast.makeText(
                                    screenContext,
                                    it.message ?: "No se pudo solicitar la copia.",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Crear copia ahora")
                }
                Text(
                    "Android protege y restaura automáticamente tema, accesibilidad, notificaciones, chat y seguridad al cambiar de dispositivo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { exportBackupLauncher.launch(backupFileName) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Exportar copia") }
                    TextButton(
                        onClick = {
                            importBackupLauncher.launch(
                                arrayOf("application/octet-stream", "application/json", "text/plain")
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Restaurar") }
                }
                Text(
                    "La copia portátil usa el formato privado .novvinbackup; no expone un archivo JSON ni incluye contraseñas, sesiones, mensajes o archivos.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection(title = "Tomar un descanso") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when (breakMode) {
                                "mute" -> "Silenciando todo"
                                "hidden" -> "Perfil oculto"
                                else -> "Desactivado"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            when (breakMode) {
                                "mute" -> "No recibirás nuevas notificaciones hasta que regreses."
                                "hidden" -> "Tu perfil no aparecerá en búsquedas ni podrá abrirse desde otras cuentas."
                                else -> "Reduce las interrupciones sin cerrar tu sesión."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = breakMode != "off",
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                showBreakModeDialog = true
                            } else {
                                val previous = breakMode
                                breakMode = "off"
                                viewModel.updateBreakMode("off") { saved ->
                                    if (!saved) breakMode = previous
                                }
                            }
                        }
                    )
                }
                if (breakMode != "off") {
                    TextButton(onClick = { showBreakModeDialog = true }) {
                        Text("Cambiar modo")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Historial de visitas al perfil", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Activado por defecto. Muestra quién visitó tu perfil y permite que otros vean tu visita. Puedes desactivarlo cuando quieras; solo se registra si ambos lo permiten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = profileViewHistoryEnabled,
                        onCheckedChange = {
                            val previous = profileViewHistoryEnabled
                            profileViewHistoryEnabled = it
                            viewModel.updateProfileViewHistory(it) { saved ->
                                if (!saved) profileViewHistoryEnabled = previous
                            }
                        }
                    )
                }
            }

            
            // Section: Pantalla
            SettingsSection(title = "Pantalla") {
                val isDarkMode by viewModel.isDarkMode.collectAsState()
                val profileAvatarVideosMuted by viewModel.profileAvatarVideosMuted.collectAsState()
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
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.LanguageSettings.route) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Language, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text("Idioma de la aplicación", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            androidx.compose.ui.platform.LocalLocale.current.platformLocale
                                .let { locale -> locale.getDisplayLanguage(locale) }
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Silenciar avatares animados", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = profileAvatarVideosMuted,
                        onCheckedChange = { viewModel.setProfileAvatarVideosMuted(it) }
                    )
                }
            }


            SettingsSection(title = "Privacidad y legales") {
                Button(
                    onClick = { navController.navigate(Screen.PrivacyPolicy.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Centro de privacidad")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { navController.navigate(Screen.TermsAndConditions.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Términos y condiciones")
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


            // Section: Soporte y Ayuda
            SettingsSection(title = "Soporte y Ayuda") {
                conectate.com.presentation.calls.IncomingCallPermissionCard()
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = { mainNavController.navigate(Screen.AccountRecoveryHelp.route) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Ayudar a recuperar una cuenta")
                }
                Text("Envía una solicitud desde esta cuenta si perdiste otra o alguien te quitó el acceso.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                var showSupportDialog by remember { mutableStateOf(false) }
                var dialogStep by remember { mutableStateOf(1) } // 1: Warning, 2: Admin Selection

                FilledTonalButton(
                    onClick = { navController.navigate(Screen.HelpSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Centro de ayuda")
                }

                val corporateEmail = "administracion@novvinapp.com"
                TextButton(
                    onClick = {
                        try {
                            screenContext.startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("mailto", corporateEmail, null)))
                        } catch (_: android.content.ActivityNotFoundException) {
                            android.widget.Toast.makeText(screenContext, "Abre tu correo y escribe a $corporateEmail", android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(corporateEmail)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                                                viewModel.contactSupport("novvin", mainNavController, context)
                                            } catch (e: Exception) {
                                                android.util.Log.e("SettingsScreen", "Error contacting support", e)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Novvin (Admin)")
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


            SettingsSection(title = "Novvin Web") {
                Text(
                    "Inicia sesión desde el navegador y vincula esta cuenta de forma segura.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = NOVVIN_WEB_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            runCatching { uriHandler.openUri(NOVVIN_WEB_URL) }
                                .onFailure {
                                    android.widget.Toast.makeText(
                                        screenContext,
                                        "No se pudo abrir el navegador.",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Computer, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Abrir web")
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard = screenContext.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE
                            ) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("Novvin Web", NOVVIN_WEB_URL)
                            )
                            android.widget.Toast.makeText(
                                screenContext,
                                "Enlace copiado.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copiar enlace")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                    Text("Vincular Novvin Web por QR")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Escanea el QR de la web para vincularla automáticamente con esta cuenta.",
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


            SettingsSection(title = "Cambio rápido de cuenta") {
                Button(
                    onClick = {
                        authViewModel.prepareAddAccount {
                            mainNavController.navigate(Screen.AddAccountLogin.route)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Añadir otra cuenta")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { authViewModel.saveCurrentAccount() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Guardar cuenta actual")
                }

                Text(
                    "Añade otra cuenta sin cerrar esta sesión. Al cerrar sesión, la cuenta actual se quita de este dispositivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (storedAccounts.isEmpty()) {
                    Text(
                        "No hay cuentas guardadas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else {
                    storedAccounts.forEach { account ->
                        val isCurrentAccount = account.user_id?.trim()?.lowercase() == currentUserId?.trim()?.lowercase()
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
                                        authViewModel.switchAccount(account.id) {
                                            mainNavController.navigate(Screen.Home.createRoute(startTab = Screen.Profile.route)) {
                                                popUpTo(mainNavController.graph.findStartDestination().id) { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        }
                                    }, enabled = !isCurrentAccount) {
                                        Text(if (isCurrentAccount) "Actual" else "Usar cuenta")
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


            // Section: Admin Panel (solo visible para admins)
            if (adminPanelAccess) {
                SettingsSection(title = "Administración") {
                    Button(
                        // Settings can live in the root graph or in Home's nested graph.
                        // The local controller is the one that always owns the matching
                        // AdminPanel destination in both cases. Using the root controller
                        // from the nested graph can throw "destination cannot be found"
                        // and terminate the app on some entry paths.
                        onClick = {
                            adminNavigationError = null
                            attemptAdminPanelNavigation(
                                navigate = {
                                    navController.navigate(Screen.AdminPanel.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onFailure = { error ->
                                    Log.e("SettingsScreen", "No se pudo abrir el panel administrativo", error)
                                    adminNavigationError =
                                        "No se pudo abrir el panel de administración. Vuelve a intentarlo."
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Panel de Administrador")
                    }
                    adminNavigationError?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                HorizontalDivider()
            }

            SettingsSection(title = "Información de la versión") {
                Text(
                    "Novvin ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Text(
                    "Compilación ${BuildConfig.BUILD_COMMIT.take(12)} · " +
                        if (BuildConfig.BUILD_ENVIRONMENT == "production") "Producción" else "Desarrollo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    "Incluye esta información cuando contactes a soporte; permite identificar exactamente qué versión está instalada.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }


            // Section: General
            SettingsSection(title = "General") {
                Button(
                    onClick = {
                        authViewModel.forceLocalLogout()
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cerrar Sesión")
                }

                Spacer(modifier = Modifier.height(16.dp))


                // Account deletion requests are available in Cuenta y seguridad;
                // the destructive operation is still performed by an administrator.
            }
        }
    }

    }

    if (showBreakModeDialog) {
        AlertDialog(
            onDismissRequest = { showBreakModeDialog = false },
            title = { Text("Tomar un descanso") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Elige cómo quieres reducir la actividad. Puedes desactivarlo en cualquier momento.")
                    FilledTonalButton(
                        onClick = {
                            showBreakModeDialog = false
                            val previous = breakMode
                            breakMode = "mute"
                            viewModel.updateBreakMode("mute") { saved ->
                                if (!saved) breakMode = previous
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Silenciar todo")
                    }
                    OutlinedButton(
                        onClick = {
                            showBreakModeDialog = false
                            val previous = breakMode
                            breakMode = "hidden"
                            viewModel.updateBreakMode("hidden") { saved ->
                                if (!saved) breakMode = previous
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ocultar perfil")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBreakModeDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showCloseAllWebSessionsDialog) {
        AlertDialog(
            onDismissRequest = { showCloseAllWebSessionsDialog = false },
            title = { Text("Cerrar sesiones web") },
            text = {
                Text("Se cerrarán todas las sesiones activas de Novvin Web vinculadas a tu cuenta.")
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

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = { Text("Solicitar eliminación de cuenta") },
            text = {
                Text(
                    "Abriremos el chat oficial de soporte con tu solicitud preparada. " +
                        "Envíala para pedir la eliminación de tu cuenta y sus datos asociados. " +
                        "Soporte confirmará tu identidad y el resultado por ese chat. Abrirlo no borra tu cuenta."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAccountDialog = false
                        viewModel.contactSupport("novvin", mainNavController, screenContext,
                            initialMessage = "Solicito la eliminación de mi cuenta Novvin y de sus datos asociados. " +
                                "Por favor, indíquenme cómo confirmar mi identidad y avísenme cuando termine el proceso.")
                    },
                    enabled = !isDeletingAccount
                ) {
                    Text("Continuar con la solicitud")
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
                            text = session.deviceLabel?.takeIf { it.isNotBlank() } ?: "Novvin Web",
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
                            Icons.AutoMirrored.Filled.Logout,
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
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
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
    val directory = LocalSettingsDirectory.current
    if (directory != null && !SettingsDirectory.matches(title, directory.query)) return
    val expanded = directory == null || directory.query.isNotBlank() || directory.expanded == title
    Column {
        Surface(
            onClick = { directory?.toggle?.invoke(title) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (directory != null) Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Contraer categoría" else "Abrir categoría",
                )
            }
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) { content() }
            }
        }
    }
}

private fun formatBackupTime(value: java.time.Instant): String =
    DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(value)

@Composable
private fun SettingsAreaButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
