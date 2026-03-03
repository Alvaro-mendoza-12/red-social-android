package conectate.com.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun formatSuspensionDateForUi(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return "nuevo aviso"
    return runCatching {
        val normalized = if (value.contains("T")) value else value.replace(" ", "T")
        val instant = Instant.parse(
            if (normalized.endsWith("Z") || normalized.contains("+")) normalized else "${normalized}Z"
        )
        instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    }.getOrDefault(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    navController: NavController,
    viewModel: AuthViewModel = hiltViewModel()
) {
    // Login type: "email" or "phone" or null (initial state showing buttons)
    var loginType by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showPhoneComingSoonDialog by remember { mutableStateOf(false) }
    var showSuspendedDialog by remember { mutableStateOf(false) }
    
    val authState by viewModel.authState.collectAsState()
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

    // Show dialog for phone auth coming soon
    if (showPhoneComingSoonDialog) {
        AlertDialog(
            onDismissRequest = { showPhoneComingSoonDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Próximamente",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "La autenticación por número de teléfono estará disponible en una próxima actualización. Por ahora, utiliza tu correo electrónico para iniciar sesión.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                TextButton(onClick = { showPhoneComingSoonDialog = false }) {
                    Text("Entendido")
                }
            }
        )
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
            text = "Bienvenido",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Show method selection or login form
        if (loginType == null) {
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
                    text = stringResource(R.string.sign_in_email),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Phone Button (Secondary - Coming Soon)
            OutlinedButton(
                onClick = { showPhoneComingSoonDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.sign_in_phone),
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

            // Back button
            TextButton(
                onClick = { loginType = null }
            ) {
                Text(
                    text = "← Volver",
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
