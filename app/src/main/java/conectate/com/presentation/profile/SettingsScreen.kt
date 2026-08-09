package conectate.com.presentation.profile

import conectate.com.BuildConfig
import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Message
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import conectate.com.data.repository.WebLoginManagedSession
import conectate.com.presentation.auth.AuthViewModel
import conectate.com.presentation.navigation.Screen

private const val NOVVIN_WEB_URL = "https://red-social-android.vercel.app"

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
    val uriHandler = LocalUriHandler.current
    val screenContext = LocalContext.current
    
    // Local state for immediate UI feedback, synced with DB
    var isPrivate by remember { mutableStateOf(false) }
    var favoritesPublic by remember { mutableStateOf(false) }
    var breakMode by remember { mutableStateOf("off") }
    var visibility by remember { mutableStateOf("everyone") }
    var initialized by remember { mutableStateOf(false) }
    var newAdditionalEmail by remember { mutableStateOf("") }
    var showCloseAllWebSessionsDialog by remember { mutableStateOf(false) }
    var showBreakModeDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var adminNavigationError by remember { mutableStateOf<String?>(null) }

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
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection(title = "Configuración por áreas") {
                SettingsAreaButton(
                    icon = Icons.Default.Message,
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

            Divider()

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
            
            Divider()

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

            SettingsSection(title = "Cuenta y seguridad") {
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

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { navController.navigate(Screen.ChatSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Message, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Preferencias de chat")
                }
            }

            Divider()

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

            Divider()

            SettingsSection(title = "Contenido sin conexión") {
                FilledTonalButton(
                    onClick = { navController.navigate(Screen.OfflineVideos.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Videos descargados")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Las descargas son privadas para esta cuenta y permanecen disponibles cuando no hay internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Divider()

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
            }

            Divider()
            
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
                            java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault())
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

            Divider()

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

            Divider()

            // Section: Soporte y Ayuda
            SettingsSection(title = "Soporte y Ayuda") {
                var showSupportDialog by remember { mutableStateOf(false) }
                var dialogStep by remember { mutableStateOf(1) } // 1: Warning, 2: Admin Selection

                FilledTonalButton(
                    onClick = { navController.navigate(Screen.HelpSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Centro de ayuda")
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

            Divider()

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

            Divider()

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

                Divider()
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

            Divider()

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


                // La eliminación de cuenta se retira del ajuste del usuario final: ahora es una
                // operación exclusiva del panel de administración (los administradores buscan la
                // cuenta y la eliminan por completo mediante el hard-delete del backend). Esto evita
                // bajas accidentales/irreversibles por parte de usuarios y centraliza la operación
                // sensible en administradores, como requiere el flujo de producción.
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
            title = { Text("Eliminar cuenta") },
            text = {
                Text(
                    "Esta accion es irreversible. Se eliminaran tu perfil, publicaciones, " +
                        "videos, mensajes y archivos asociados a la cuenta."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAccountDialog = false
                        viewModel.deleteOwnAccount()
                    },
                    enabled = !isDeletingAccount
                ) {
                    Text("Eliminar definitivamente", color = MaterialTheme.colorScheme.error)
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

@Composable
private fun SettingsAreaButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}
