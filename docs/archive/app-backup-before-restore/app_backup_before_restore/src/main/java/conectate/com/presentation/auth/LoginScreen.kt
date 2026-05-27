package conectate.com.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.R
import conectate.com.data.model.StoredAccountSession
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.navigation.Screen
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

private fun formatSuspensionDateForUi(raw: String?): String {
    var value = raw?.trim().orEmpty()
    if (value.isBlank()) return "nuevo aviso"
    return runCatching {
        val normalized = if (value.contains("T")) value else value.replace(" ", "T")
        val instant = Instant.parse(
            if (normalized.endsWith("Z") || normalized.contains("+")) normalized else "${normalized}Z"
        )
        instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    }.getOrDefault(value)
}



@Composable
fun LoginScreen(
    navController: NavController,
    isAddAccountMode: Boolean = false,
    viewModel: AuthViewModel = hiltViewModel()
) {
    // Login type: "email" or null (initial state showing buttons)
    var loginType by rememberSaveable { mutableStateOf(if (isAddAccountMode) "email" else null) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var showSuspendedDialog by rememberSaveable { mutableStateOf(false) }
    var switchingAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val storedAccounts by viewModel.storedAccounts.collectAsStateWithLifecycle()
    val accountSwitchMessage by viewModel.accountSwitchMessage.collectAsStateWithLifecycle()
    val currentUserId by viewModel.currentUserId.collectAsStateWithLifecycle()
    val suspendedState = authState as? AuthState.Suspended
    val authErrorText = (authState as? AuthState.Error)?.message.orEmpty()
    val suspensionEndDate = suspendedState?.suspensionEndDate
    val isSuspensionError = suspendedState != null ||
        authErrorText.contains("suspendida", ignoreCase = true) ||
        authErrorText.contains("inhabilitada", ignoreCase = true) ||
        authErrorText.contains("ACCOUNT_SUSPENDED", ignoreCase = true)

    LaunchedEffect(authErrorText, suspendedState) {
        if (isSuspensionError) {
            showSuspendedDialog = true
        }
    }

    LaunchedEffect(authState) {
        if (authState !is AuthState.Loading) {
            switchingAccountId = null
        }
    }

    LaunchedEffect(authState, isAddAccountMode) {
        if (!isAddAccountMode || authState !is AuthState.Success) return
        navController.navigate(Screen.Home.createRoute(startTab = "messages")) {
            popUpTo(Screen.Login.route) { inclusive = true }
            launchSingleTop = true
        }
    }

    if (showSuspendedDialog && isSuspensionError) {
        val suspensionDateText = formatSuspensionDateForUi(suspensionEndDate)
        val suspensionReason = suspendedState?.reason?.trim().orEmpty()
        val permanent = suspensionEndDate.isNullOrBlank()
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Cuenta suspendida") },
            text = {
                val message = buildString {
                    append("Tu cuenta ha sido suspendida por no cumplir las normas de la comunidad o políticas de privacidad.")
                    if (suspensionReason.isNotBlank()) {
                        append("\n\nMotivo: $suspensionReason")
                    }
                    append(
                        if (permanent) {
                            "\n\nLa suspensión es permanente."
                        } else {
                            "\n\nSuspendida hasta:\n$suspensionDateText\n\nVuelve en esa fecha para poder iniciar sesión."
                        }
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSuspendedDialog = false
                        viewModel.resetAuthState()
                        email = ""
                        password = ""
                        loginType = "email"
                    }
                ) {
                    Text("Aceptar")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Title
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isAddAccountMode) "Agregar otra cuenta" else "Bienvenido",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        if (isAddAccountMode) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Inicia sesión con la cuenta que quieres sumar. Luego podrás alternarla desde Mensajes y Ajustes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
                textAlign = TextAlign.Center
            )
        }

        if (!accountSwitchMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (authState is AuthState.Error) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = accountSwitchMessage.orEmpty(),
                    modifier = Modifier.padding(16.dp),
                    color = if (authState is AuthState.Error) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            LaunchedEffect(accountSwitchMessage) {
                if (!accountSwitchMessage.isNullOrBlank()) {
                    kotlinx.coroutines.delay(2600)
                    viewModel.clearAccountSwitchMessage()
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Show method selection or login form
        if (!isAddAccountMode && loginType == null) {
            if (storedAccounts.isNotEmpty()) {
                SavedAccountsSection(
                    accounts = storedAccounts,
                    switchingAccountId = switchingAccountId,
                    onUseAccount = { account ->
                        switchingAccountId = account.id
                        viewModel.switchAccount(account.id)
                    },
                    onRemoveAccount = { account ->
                        viewModel.removeStoredAccount(account.id)
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "o entra manualmente",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Method Selection Buttons
            Text(
                text = "Elige cómo quieres iniciar sesión",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Email Button (Primary)
            Button(
                onClick = { loginType = "email" },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (storedAccounts.isNotEmpty()) {
                        "Iniciar sesión con otra cuenta"
                    } else {
                        stringResource(R.string.sign_in_email)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    if (currentUserId.isNullOrBlank() && storedAccounts.isEmpty()) {
                        viewModel.setAuthError(
                            "Primero inicia sesión o guarda una cuenta en este dispositivo para aprobar un acceso por QR."
                        )
                        return
                    }
                    navController.navigate(
                        Screen.QrScanner.createRoute(
                            source = "login",
                            mode = "web_only"
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Escanear QR de Conectate Web",
                    style = MaterialTheme.typography.titleMedium
                )
            }

        } else if (loginType == "email") {
            // Email Login Form
            // Email Field
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.email)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null
                    )
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Password Field
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    val image = if (passwordVisible)
                        Icons.Filled.Visibility
                    else
                        Icons.Filled.VisibilityOff

                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(imageVector = image, contentDescription = if (passwordVisible) "Ocultar contraseña" else "Ver contraseña")
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Login Button
            Button(
                onClick = {
                    if (email.isNotBlank() && password.isNotBlank()) {
                        viewModel.signInWithEmail(email, password)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = authState !is AuthState.Loading && 
                         email.isNotBlank() && password.isNotBlank()
            ) {
                if (authState is AuthState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = stringResource(R.string.login),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Forgot Password Link
            TextButton(
                onClick = {
                    val trimmedEmail = email.trim()
                    if (trimmedEmail.isNotBlank()) {
                        viewModel.resetPassword(trimmedEmail)
                    } else {
                        // Notify user that email is required
                        viewModel.setAuthError("Por favor, ingresa tu correo electrónico para restablecer la contraseña.")
                    }
                },
                enabled = authState !is AuthState.Loading
            ) {
                Text(
                    text = "¿Olvidaste tu contraseña?",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Register Button
            if (!isAddAccountMode) {
                TextButton(
                    onClick = {
                        navController.navigate("register")
                    }
                ) {
                    Text(
                        text = stringResource(R.string.dont_have_account) + " " +
                            stringResource(R.string.register),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Back button
            TextButton(
                onClick = {
                    if (isAddAccountMode) {
                        navController.popBackStack()
                    } else {
                        loginType = null
                    }
                }
            ) {
                Text(
                    text = if (isAddAccountMode) "← Volver a mensajes" else "← Volver",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            // Show success for password reset
            if (authState is AuthState.PasswordResetSent) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Correo enviado",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Revisa tu bandeja de entrada para restablecer tu contraseña.",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Show error if exists
            if (authState is AuthState.Error && !isSuspensionError) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = (authState as AuthState.Error).message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}


private fun SavedAccountsSection(
    accounts: List<StoredAccountSession>,
    switchingAccountId: String?,
    onUseAccount: (StoredAccountSession) -> Unit,
    onRemoveAccount: (StoredAccountSession) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Cuentas guardadas",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Toca una cuenta para entrar rápido sin volver a escribir correo y contraseña.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
        )

        accounts.forEach { account ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProfileImage(
                        imageUrl = account.avatar_url,
                        size = 44.dp,
                        showBorder = true,
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.display_name?.ifBlank { account.email } ?: account.email,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formatStoredAccountLastUsed(account.last_used_at),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onRemoveAccount(account) }) {
                        Text("Quitar")
                    }
                    Button(
                        onClick = { onUseAccount(account) },
                        enabled = switchingAccountId != account.id
                    ) {
                        if (switchingAccountId == account.id) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Entrar")
                        }
                    }
                }
            }
        }
    }
}

private fun formatStoredAccountLastUsed(timestamp: Long): String {
    if (timestamp <= 0L) return "Cuenta guardada"

    val now = LocalDateTime.now()
    val usedAt = runCatching {
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        )
    }.getOrNull() ?: return "Cuenta guardada"

    var daysDifference = java.time.Duration.between(usedAt, now).toDays().absoluteValue
    return when {
        usedAt.toLocalDate() == now.toLocalDate() -> "Usada hoy"
        daysDifference == 1L -> "Usada ayer"
        daysDifference in 2..6 -> "Usada hace ${daysDifference} dias"
        else -> "Usada el " + usedAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }
}







