package conectate.com.presentation.auth

import android.util.Patterns
import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.R
import conectate.com.auth.twoFactor.TwoFactorAuthManager
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
    // Entry, email sign-in, or password recovery.
    val context = LocalContext.current
    var showLocalTwoFactorResetNotice by rememberSaveable {
        mutableStateOf(TwoFactorAuthManager.hasLocalStoreResetNotice(context))
    }
    var loginType by rememberSaveable { mutableStateOf(if (isAddAccountMode) "email" else null) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var secondFactorCode by remember { mutableStateOf("") }
    var needsTwoFactor by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var migrationEmail by rememberSaveable { mutableStateOf("") }
    var migrationDisplayName by rememberSaveable { mutableStateOf("") }
    var migrationToken by rememberSaveable { mutableStateOf("") }
    var migrationNewPassword by rememberSaveable { mutableStateOf("") }
    var migrationConfirmPassword by rememberSaveable { mutableStateOf("") }
    var recoveryCodeRequired by rememberSaveable { mutableStateOf(false) }
    var migrationInfo by rememberSaveable { mutableStateOf("") }
    var passwordFlowMode by rememberSaveable { mutableStateOf("migration") }
    var showSuspendedDialog by rememberSaveable { mutableStateOf(false) }
    var showAppealForm by rememberSaveable { mutableStateOf(false) }
    var appealStatement by rememberSaveable { mutableStateOf("") }
    var switchingAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val storedAccounts by viewModel.storedAccounts.collectAsStateWithLifecycle()
    val accountSwitchMessage by viewModel.accountSwitchMessage.collectAsStateWithLifecycle()
    val appealSubmitting by viewModel.appealSubmitting.collectAsStateWithLifecycle()
    val appealMessage by viewModel.appealMessage.collectAsStateWithLifecycle()
    val currentUserId by viewModel.currentUserId.collectAsStateWithLifecycle()
    val suspendedState = authState as? AuthState.Suspended
    val authErrorText = (authState as? AuthState.Error)?.message.orEmpty()
    val emailIsValid = email.isBlank() || Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
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

        when (val state = authState) {
            is AuthState.TwoFactorRequired -> {
                needsTwoFactor = true
            }
            AuthState.PasswordChangedNeedsTwoFactor -> {
                loginType = "email"
                if (migrationEmail.isNotBlank()) email = migrationEmail
                password = migrationNewPassword
                migrationEmail = ""
                migrationToken = ""
                migrationNewPassword = ""
                migrationConfirmPassword = ""
                recoveryCodeRequired = false
                needsTwoFactor = true
            }
            is AuthState.MigratedPasswordSetupRequired -> {
                val resolvedEmail = state.email.ifBlank { email.trim().lowercase() }
                loginType = "email"
                passwordFlowMode = "migration"
                migrationEmail = resolvedEmail
                migrationDisplayName = state.displayName.orEmpty()
                migrationToken = state.migrationToken.orEmpty()
                migrationNewPassword = ""
                migrationConfirmPassword = ""
                recoveryCodeRequired = false
                migrationInfo =
                    "Verificamos tu contraseña actual. Ahora crea una nueva contraseña una sola vez para volver a entrar con normalidad."
                if (resolvedEmail.isNotBlank()) email = resolvedEmail
                password = ""
            }
            is AuthState.MigrationPasswordCodeSent -> {
                val resolvedEmail = state.email.ifBlank { email.trim().lowercase() }
                loginType = "recovery"
                passwordFlowMode = "reset"
                migrationEmail = resolvedEmail
                migrationDisplayName = ""
                migrationToken = state.resetToken.orEmpty()
                migrationNewPassword = ""
                migrationConfirmPassword = ""
                recoveryCodeRequired = state.resetToken.isNullOrBlank()
                migrationInfo = if (state.resetToken.isNullOrBlank()) {
                    "Si el correo está registrado, recibirás un código de recuperación. Cópialo aquí antes de ${state.expiresInMinutes ?: 30} minutos."
                } else {
                    "Solicitud verificada por el servidor. Crea ahora una contraseña nueva; el permiso vence en ${state.expiresInMinutes ?: 30} minutos."
                }
                if (resolvedEmail.isNotBlank()) email = resolvedEmail
                password = ""
            }
            is AuthState.Success -> {
                needsTwoFactor = false
                secondFactorCode = ""
                migrationEmail = ""
                migrationDisplayName = ""
                migrationToken = ""
                migrationNewPassword = ""
                migrationConfirmPassword = ""
                recoveryCodeRequired = false
                migrationInfo = ""
                passwordFlowMode = "migration"
            }
            else -> Unit
        }
    }

    LaunchedEffect(authState, isAddAccountMode) {
        if (!isAddAccountMode || authState !is AuthState.Success) {
            return@LaunchedEffect
        }
        navController.navigate(Screen.Home.createRoute(startTab = "messages")) {
            popUpTo(Screen.AddAccountLogin.route) { inclusive = true }
            launchSingleTop = true
        }
    }

    if (showLocalTwoFactorResetNotice && !isSuspensionError) {
        AlertDialog(
            onDismissRequest = {
                TwoFactorAuthManager.dismissLocalStoreResetNotice(context)
                showLocalTwoFactorResetNotice = false
            },
            title = { Text(stringResource(R.string.two_factor_local_reset_title)) },
            text = { Text(stringResource(R.string.two_factor_local_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    TwoFactorAuthManager.dismissLocalStoreResetNotice(context)
                    showLocalTwoFactorResetNotice = false
                }) { Text(stringResource(R.string.two_factor_local_reset_ok)) }
            },
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
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Tu cuenta fue restringida por una decisión de moderación.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    suspendedState?.ruleTitle?.takeIf { it.isNotBlank() }?.let {
                        Text("Regla aplicada: $it", fontWeight = FontWeight.SemiBold)
                    }
                    val explanation = suspendedState?.explanation
                        ?.takeIf { it.isNotBlank() }
                        ?: suspensionReason.takeIf { it.isNotBlank() }
                    explanation?.let { Text("Explicación: $it") }
                    suspendedState?.evidence?.takeIf { it.isNotEmpty() }?.let { evidence ->
                        Text("Evidencia considerada:", fontWeight = FontWeight.SemiBold)
                        evidence.take(5).forEach { Text("• $it") }
                    }
                    Text(
                        if (permanent) {
                            "La suspensión es permanente."
                        } else {
                            "Suspendida hasta: $suspensionDateText"
                        }
                    )
                    suspendedState?.decisionId?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "Decisión: ${it.take(12)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when (suspendedState?.appealStatus) {
                        "pending", "reviewing" -> Text(
                            "Tu apelación está pendiente de revisión humana.",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        "upheld" -> Text(
                            suspendedState?.appealReviewerNote
                                ?: "La revisión mantuvo la decisión.",
                            color = MaterialTheme.colorScheme.error
                        )
                        "overturned" -> Text(
                            "La apelación fue aceptada. Vuelve a iniciar sesión.",
                            color = MaterialTheme.colorScheme.primary
                        )
                        else -> {
                            if (!suspendedState?.appealToken.isNullOrBlank()) {
                                if (showAppealForm) {
                                    OutlinedTextField(
                                        value = appealStatement,
                                        onValueChange = { appealStatement = it.take(2000) },
                                        label = { Text("Explica por qué debe revisarse") },
                                        supportingText = {
                                            Text("${appealStatement.trim().length}/2000 · mínimo 20")
                                        },
                                        minLines = 3,
                                        maxLines = 6,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        enabled = !appealSubmitting && appealStatement.trim().length >= 20,
                                        onClick = { viewModel.submitModerationAppeal(appealStatement) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (appealSubmitting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text("Enviar apelación")
                                        }
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { showAppealForm = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Apelar esta decisión")
                                    }
                                }
                            }
                        }
                    }
                    appealMessage?.let {
                        Text(
                            it,
                            color = if (it.startsWith("Apelación enviada"))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSuspendedDialog = false
                        viewModel.resetAuthState()
                        email = ""
                        password = ""
                        loginType = "email"
                        showAppealForm = false
                        appealStatement = ""
                    }
                ) {
                    Text("Aceptar")
                }
            }
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .testTag("auth-login")
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer,
                    )
                )
            )
    ) {
        val responsiveHorizontalPadding =
            if (maxWidth > 600.dp) ((maxWidth - 520.dp) / 2).coerceAtLeast(20.dp) else 20.dp
        val availableHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = responsiveHorizontalPadding, vertical = 24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
        AuthBrandHeader(
            title = when {
                isAddAccountMode -> "Agrega otra cuenta"
                loginType == "recovery" -> "Recupera tu cuenta"
                loginType == "email" -> "Qué bueno verte"
                else -> "Tu mundo empieza aquí"
            },
            subtitle = when {
                isAddAccountMode -> "Entra con la cuenta que quieres sumar. Podrás alternar entre ellas después."
                loginType == "recovery" -> "Te ayudaremos a volver a entrar de forma segura."
                loginType == "email" -> "Entra para seguir donde dejaste tus conversaciones y momentos."
                else -> "Mensajes, videos y momentos con tu gente en un solo lugar."
            },
            compact = loginType != null || isAddAccountMode,
            introTopSpace = if (loginType == null && !isAddAccountMode) {
                ((availableHeight - 620.dp) * 0.55f).coerceIn(0.dp, 140.dp)
            } else 0.dp,
            onBack = if (loginType != null || isAddAccountMode) {
                {
                    if (isAddAccountMode) navController.popBackStack()
                    else if (loginType == "recovery") {
                        loginType = "email"
                        migrationEmail = ""
                        migrationToken = ""
                        migrationNewPassword = ""
                        migrationConfirmPassword = ""
                        recoveryCodeRequired = false
                        migrationInfo = ""
                        viewModel.resetAuthState()
                    } else loginType = null
                }
            } else null,
        )

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

        if (authErrorText.isNotBlank() && !isSuspensionError) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = authErrorText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (loginType == "email" && authErrorText.startsWith("Contraseña incorrecta")) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "¿Tu cuenta es anterior al cambio de servidor y estás seguro de la contraseña? Puede necesitar recuperación asistida. Evita crear otra cuenta para conservar tu perfil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

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

            // Email Button (Primary)
            Button(
                onClick = { loginType = "email" },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
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
                        "Iniciar sesión"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!isAddAccountMode) {
                OutlinedButton(
                    onClick = { navController.navigate(Screen.Register.route) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Crear una cuenta nueva",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            if (!currentUserId.isNullOrBlank() || storedAccounts.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        navController.navigate(Screen.WebLoginScanner.createRoute()) {
                            launchSingleTop = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Escanear QR de Novvin Web",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

        } else if (loginType == "email" || loginType == "recovery") {
            val recovering = loginType == "recovery"
            // Email Login Form
            if (recovering) {
                Text(
                    text = "Escribe el correo vinculado a tu cuenta.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }

            // Email Field
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    needsTwoFactor = false
                    secondFactorCode = ""
                    if (migrationEmail.isNotBlank() &&
                        !migrationEmail.equals(it.trim(), ignoreCase = true)) {
                        migrationEmail = ""
                        migrationToken = ""
                        migrationNewPassword = ""
                        migrationConfirmPassword = ""
                        recoveryCodeRequired = false
                        migrationInfo = ""
                    }
                    if (authState is AuthState.Error) viewModel.resetAuthState()
                },
                label = { Text("Correo de tu cuenta") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                isError = !emailIsValid,
                supportingText = {
                    if (!emailIsValid) Text("Escribe un correo válido")
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null
                    )
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Password Field
            if (!recovering) {
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    needsTwoFactor = false
                    secondFactorCode = ""
                    if (authState is AuthState.Error) viewModel.resetAuthState()
                },
                label = { Text("Tu contraseña") },
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
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
            }

            Spacer(modifier = Modifier.height(if (recovering) 12.dp else 24.dp))

            if (needsTwoFactor && !recovering) {
                OutlinedTextField(
                    value = secondFactorCode,
                    onValueChange = { secondFactorCode = it.take(32) },
                    label = { Text("Código de verificación o respaldo") },
                    supportingText = {
                        Text((authState as? AuthState.TwoFactorRequired)?.message
                            ?: "Abre tu autenticador. También puedes usar un código de respaldo sin utilizar.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Login Button
            if (!recovering) {
            Button(
                onClick = {
                    if (email.isNotBlank() && password.isNotBlank()) {
                        migrationEmail = ""
                        migrationDisplayName = ""
                        migrationToken = ""
                        migrationNewPassword = ""
                        migrationConfirmPassword = ""
                        migrationInfo = ""
                        viewModel.signInWithEmail(email, password, secondFactorCode.takeIf(String::isNotBlank))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
                enabled = authState !is AuthState.Loading && 
                         email.isNotBlank() && emailIsValid && password.isNotBlank() &&
                         (!needsTwoFactor || secondFactorCode.isNotBlank())
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
            }

            if (recovering) {
                if (migrationEmail.isBlank()) {
                    Button(
                        onClick = {
                            passwordFlowMode = "reset"
                            viewModel.requestMigratedPasswordCode(email.trim().lowercase())
                        },
                        enabled = authState !is AuthState.Loading && email.isNotBlank() && emailIsValid,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (authState is AuthState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Continuar con recuperación")
                        }
                    }
                    TextButton(
                        onClick = {
                            viewModel.resetAuthState()
                            passwordFlowMode = "saved_code"
                            migrationEmail = email.trim().lowercase()
                            migrationDisplayName = ""
                            migrationToken = ""
                            migrationNewPassword = ""
                            migrationConfirmPassword = ""
                            recoveryCodeRequired = true
                            migrationInfo = "Usa un código que guardaste fuera del teléfono anterior. Cada código funciona una sola vez."
                        },
                        enabled = authState !is AuthState.Loading && email.isNotBlank() && emailIsValid,
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Tengo un código guardado") }
                }
            } else {
                TextButton(
                    onClick = { loginType = "recovery" },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("¿Olvidaste tu contraseña?")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (migrationEmail.isNotBlank()) {
                if (passwordFlowMode == "saved_code") {
                    OutlinedTextField(
                        value = secondFactorCode,
                        onValueChange = { secondFactorCode = it.take(32) },
                        label = { Text("Código de autenticador o respaldo (si activaste 2 pasos)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                MigratedPasswordSetupCard(
                    email = migrationEmail,
                    displayName = migrationDisplayName,
                    newPassword = migrationNewPassword,
                    confirmPassword = migrationConfirmPassword,
                    recoveryCodeRequired = recoveryCodeRequired,
                    recoveryCode = migrationToken,
                    recoveryCodeHint = if (passwordFlowMode == "saved_code") {
                        "Escribe un código que guardaste antes de perder el acceso."
                    } else {
                        "Copia el código recibido en tu correo."
                    },
                    info = migrationInfo,
                    isLoading = authState is AuthState.Loading,
                    onNewPasswordChange = { migrationNewPassword = it },
                    onConfirmPasswordChange = { migrationConfirmPassword = it },
                    onRecoveryCodeChange = { migrationToken = it.trim() },
                    canSubmit = migrationToken.isNotBlank(),
                    submitLabel = if (passwordFlowMode == "reset") {
                        "Guardar nueva contraseña y entrar"
                    } else {
                        "Guardar contraseña y entrar"
                    },
                    onSubmit = {
                        val nextPassword = migrationNewPassword
                        if (nextPassword.length < 8) {
                            viewModel.setAuthError("La nueva contraseña debe tener al menos 8 caracteres.")
                            return@MigratedPasswordSetupCard
                        }
                        if (nextPassword != migrationConfirmPassword) {
                            viewModel.setAuthError("La confirmación no coincide con la contraseña nueva.")
                            return@MigratedPasswordSetupCard
                        }
                        if (passwordFlowMode == "saved_code") {
                            viewModel.confirmSavedPasswordRecoveryCode(
                                email = migrationEmail,
                                recoveryCode = migrationToken,
                                newPassword = nextPassword,
                                secondFactorCode = secondFactorCode.takeIf(String::isNotBlank)
                            )
                        } else if (passwordFlowMode == "reset") {
                            viewModel.confirmMigratedPasswordCode(
                                resetToken = migrationToken,
                                newPassword = nextPassword
                            )
                        } else {
                            viewModel.setupMigratedPassword(
                                email = migrationEmail,
                                migrationToken = migrationToken,
                                newPassword = nextPassword
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Register Button
            if (!isAddAccountMode && !recovering) {
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

            // Show success for legacy password reset only if a legacy flow explicitly uses it.
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
                            text = "Cambio preparado",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Crea tu nueva contraseña local para entrar con Ubuntu.",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

        }
        }
    }
}

@Composable
private fun MigratedPasswordSetupCard(
    email: String,
    displayName: String,
    newPassword: String,
    confirmPassword: String,
    recoveryCodeRequired: Boolean,
    recoveryCode: String,
    recoveryCodeHint: String,
    info: String,
    isLoading: Boolean,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onRecoveryCodeChange: (String) -> Unit,
    canSubmit: Boolean,
    submitLabel: String,
    onSubmit: () -> Unit
) {
    var newPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmPasswordVisible by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Cambia tu contraseña",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = buildString {
                    if (displayName.isNotBlank()) append("$displayName, ")
                    append(info.ifBlank { "Tu cuenta necesita una contraseña nueva en Ubuntu." })
                    append("\nCuenta: $email")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            if (recoveryCodeRequired) {
                OutlinedTextField(
                    value = recoveryCode,
                    onValueChange = onRecoveryCodeChange,
                    label = { Text("Código de recuperación") },
                    supportingText = { Text(recoveryCodeHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading,
                )
            }

            OutlinedTextField(
                value = newPassword,
                onValueChange = onNewPasswordChange,
                label = { Text("Nueva contraseña") },
                visualTransformation = if (newPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoading,
                trailingIcon = {
                    IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                        Icon(
                            imageVector = if (newPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (newPasswordVisible) "Ocultar nueva contraseña" else "Ver nueva contraseña",
                        )
                    }
                },
            )

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = onConfirmPasswordChange,
                label = { Text("Repetir nueva contraseña") },
                visualTransformation = if (confirmPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoading,
                trailingIcon = {
                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                        Icon(
                            imageVector = if (confirmPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (confirmPasswordVisible) "Ocultar confirmación" else "Ver confirmación",
                        )
                    }
                },
            )

            Button(
                onClick = onSubmit,
                enabled = !isLoading && canSubmit && newPassword.length >= 8 && confirmPassword.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(submitLabel)
                }
            }
        }
    }
}

@Composable
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
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                text = account.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Text(
                                text = formatStoredAccountLastUsed(account.last_used_at),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onRemoveAccount(account) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Quitar") }
                        Button(
                            onClick = { onUseAccount(account) },
                            enabled = switchingAccountId != account.id,
                            modifier = Modifier.weight(1f),
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







