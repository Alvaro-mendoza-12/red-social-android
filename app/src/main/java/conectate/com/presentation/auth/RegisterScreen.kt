package conectate.com.presentation.auth

import androidx.compose.runtime.Composable

import android.net.Uri
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavBackStackEntry
import coil.compose.rememberAsyncImagePainter
import conectate.com.R
import conectate.com.presentation.components.ImageCropPreset
import conectate.com.presentation.components.SimpleImageCropDialog
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.navigation.Screen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import conectate.com.data.model.suggestedPublicUsername
import conectate.com.data.model.publicUsernameFormatError
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    navController: NavController,
    registerBackStackEntry: NavBackStackEntry,
    viewModel: AuthViewModel = hiltViewModel()
) {
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var registrationStep by rememberSaveable { mutableIntStateOf(0) }
    var usernameAvailable by rememberSaveable { mutableStateOf(false) }
    var usernameChecking by remember { mutableStateOf(false) }
    var usernameError by remember { mutableStateOf("") }
    var usernameSuggestions by remember { mutableStateOf(emptyList<String>()) }
    var autoSuggestUsername by rememberSaveable { mutableStateOf(false) }
    var usernameCheckAttempt by remember { mutableIntStateOf(0) }
    var usernameCheckFailed by remember { mutableStateOf(false) }
    var showUsernameSuggestions by remember { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var confirmPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var country by rememberSaveable { mutableStateOf("") }
    var gender by rememberSaveable { mutableStateOf("unspecified") }
    var birthDate by rememberSaveable { mutableStateOf("") }
    var profileImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingProfileCropUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var acceptedPrivacyPolicy by rememberSaveable { mutableStateOf(false) }
    
    var showCountryDropdown by rememberSaveable { mutableStateOf(false) }
    var showGenderDropdown by rememberSaveable { mutableStateOf(false) }
    var showBirthDatePicker by rememberSaveable { mutableStateOf(false) }
    
    val authState by viewModel.authState.collectAsState()
    val scrollState = rememberScrollState()
    val authError = (authState as? AuthState.Error)?.message.orEmpty()
    val emailIsValid = email.isBlank() || Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val parsedBirthDate = remember(birthDate) {
        runCatching { LocalDate.parse(birthDate) }.getOrNull()
    }
    val birthDateIsValid = parsedBirthDate?.let { !it.isAfter(LocalDate.now()) } == true
    val privacyAcceptedFromDocument by registerBackStackEntry.savedStateHandle
        .getStateFlow("registration_privacy_accepted", false)
        .collectAsState()

    LaunchedEffect(privacyAcceptedFromDocument) {
        if (privacyAcceptedFromDocument) acceptedPrivacyPolicy = true
    }

    BackHandler(enabled = registrationStep > 0 && authState !is AuthState.Loading) {
        registrationStep -= 1
    }

    LaunchedEffect(authError) {
        if (authError.contains("@usuario", ignoreCase = true)) {
            registrationStep = 1
            usernameAvailable = false
            autoSuggestUsername = false
            usernameCheckAttempt += 1
        }
    }

    LaunchedEffect(username, name, registrationStep, usernameCheckAttempt) {
        if (registrationStep != 1) return@LaunchedEffect
        usernameAvailable = false
        usernameSuggestions = emptyList()
        usernameError = ""
        usernameChecking = false
        usernameCheckFailed = false
        showUsernameSuggestions = false
        val candidate = username.trim().removePrefix("@").lowercase(Locale.ROOT)
        val formatError = publicUsernameFormatError(candidate)
        if (formatError != null) {
            usernameError = formatError
            return@LaunchedEffect
        }
        usernameChecking = true
        delay(400)
        val result = viewModel.checkUsernameAvailability(candidate, name)
        coroutineContext.ensureActive()
        usernameChecking = false
        result.fold(
            onSuccess = {
                if (autoSuggestUsername && !it.available && it.suggestions.isNotEmpty()) {
                    username = it.suggestions.first()
                    return@fold
                }
                autoSuggestUsername = false
                usernameAvailable = it.available
                usernameError = when (it.reason) {
                    "USERNAME_TAKEN" -> "Este @usuario ya está en uso. Elige otro."
                    "USERNAME_RESERVED" -> "Este @usuario está reservado. Elige otro."
                    "USERNAME_INVALID" -> "Este @usuario no es válido."
                    else -> if (it.available) "" else "Este @usuario no está disponible."
                }
                usernameSuggestions = it.suggestions
            },
            onFailure = {
                usernameCheckFailed = true
                usernameError = "No se pudo comprobar el @usuario. Revisa la conexión y pulsa Reintentar."
            }
        )
    }

    // Lista de países (principales de América Latina)
    val countries = listOf(
        "Argentina", "Bolivia", "Brasil", "Chile", "Colombia", 
        "Costa Rica", "Cuba", "Ecuador", "El Salvador", "Guatemala",
        "Honduras", "México", "Nicaragua", "Panamá", "Paraguay",
        "Perú", "Puerto Rico", "República Dominicana", "Uruguay", "Venezuela",
        "España", "Estados Unidos", "Otro"
    )
    

    
    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        pendingProfileCropUri = uri
    }

    pendingProfileCropUri?.let { sourceUri ->
        SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Recortar foto de perfil",
            presets = listOf(ImageCropPreset.SQUARE),
            initialPreset = ImageCropPreset.SQUARE,
            onDismiss = { pendingProfileCropUri = null },
            onCropped = { croppedUri ->
                profileImageUri = croppedUri
                pendingProfileCropUri = null
            }
        )
    }

    if (showBirthDatePicker) {
        val initialDateMillis = parsedBirthDate
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli()
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialDateMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showBirthDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedMillis ->
                            val selected = Instant.ofEpochMilli(selectedMillis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            if (selected.isAfter(LocalDate.now())) {
                                AppFeedbackBus.showError("La fecha de nacimiento no puede estar en el futuro.")
                            } else {
                                birthDate = selected.format(DateTimeFormatter.ISO_LOCAL_DATE)
                                showBirthDatePicker = false
                            }
                        }
                    },
                ) { Text("Usar fecha") }
            },
            dismissButton = {
                TextButton(onClick = { showBirthDatePicker = false }) { Text("Cancelar") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
    
    BoxWithConstraints(
        modifier = Modifier
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
            if (maxWidth > 600.dp) ((maxWidth - 560.dp) / 2).coerceAtLeast(20.dp) else 20.dp
        if (registrationStep < 2) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = responsiveHorizontalPadding, vertical = 24.dp),
            ) {
                AuthBrandHeader(
                    title = if (registrationStep == 0) "¿Cómo te llamas?" else "Elige tu @usuario",
                    subtitle = if (registrationStep == 0)
                        "Este nombre se mostrará en tu perfil y puede coincidir con el de otras personas."
                    else "Tu @usuario será único y permanente. Servirá para encontrarte y mencionarte.",
                    compact = true,
                    onBack = {
                        if (registrationStep == 1) registrationStep = 0 else navController.popBackStack()
                    },
                )
                Spacer(Modifier.height(32.dp))
                if (registrationStep == 0) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        label = { Text("Nombre visible") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = {
                            if (name.trim().length >= 2) {
                                if (username.isBlank()) {
                                    username = suggestedPublicUsername(name)
                                    autoSuggestUsername = true
                                }
                                registrationStep = 1
                            }
                        }),
                        supportingText = { Text("Podrás cambiarlo más adelante.") },
                    )
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            autoSuggestUsername = false
                            username = it.removePrefix("@").lowercase(Locale.ROOT).take(25)
                            viewModel.resetAuthState()
                        },
                        label = { Text("Identificador único") },
                        prefix = { Text("@") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (usernameAvailable && !usernameChecking) registrationStep = 2
                        }),
                        isError = usernameError.isNotBlank(),
                        supportingText = {
                            Text(when {
                                usernameChecking -> "Comprobando disponibilidad…"
                                usernameAvailable -> "Disponible. Será tu identificador permanente."
                                usernameError.isNotBlank() -> usernameError
                                else -> "Solo letras minúsculas, números, puntos, guiones y _."
                            }, color = if (usernameError.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        },
                    )
                    if (usernameCheckFailed) {
                        TextButton(onClick = { usernameCheckAttempt += 1 }) { Text("Reintentar comprobación") }
                    }
                    if (usernameSuggestions.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { showUsernameSuggestions = !showUsernameSuggestions }) {
                            Text("Nombres recomendados")
                        }
                        if (showUsernameSuggestions) usernameSuggestions.forEach { suggestion ->
                            TextButton(onClick = { autoSuggestUsername = false; username = suggestion }) { Text("@$suggestion") }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = {
                        if (registrationStep == 0) {
                            if (name.trim().length < 2) {
                                AppFeedbackBus.showError("Escribe un nombre de al menos dos caracteres.")
                            } else {
                                if (username.isBlank()) {
                                    username = suggestedPublicUsername(name)
                                    autoSuggestUsername = true
                                }
                                registrationStep = 1
                            }
                        } else if (usernameAvailable && !usernameChecking) registrationStep = 2
                    },
                    enabled = registrationStep == 0 || (usernameAvailable && !usernameChecking),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Continuar") }
            }
        } else Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = responsiveHorizontalPadding, vertical = 24.dp),
            horizontalAlignment = Alignment.Start
        ) {
        AuthBrandHeader(
            title = "Crea tu espacio",
            subtitle = "Una cuenta para conversar, compartir momentos y descubrir lo que te gusta.",
            compact = true,
            onBack = { registrationStep = 1 },
        )

        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = "Tu perfil",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (authError.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(authError, modifier = Modifier.padding(16.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Profile Image Picker
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { imagePickerLauncher.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) {
                if (profileImageUri != null) {
                    Image(
                        painter = rememberAsyncImagePainter(profileImageUri),
                        contentDescription = "Previsualización de la foto de perfil",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Agregar foto de perfil",
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .clickable { imagePickerLauncher.launch("image/*") },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 3.dp,
            ) {
                Icon(
                    Icons.Default.Crop,
                    contentDescription = "Elegir y recortar foto",
                    modifier = Modifier.padding(6.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Text(
            text = if (profileImageUri == null) {
                "Agregar foto (opcional) · podrás mover y recortar antes de guardar"
            } else {
                "Vista previa lista · toca para cambiar o recortar"
            },
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("$name · @$username", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { registrationStep = 0 }) { Text("Cambiar nombre o @usuario") }
        
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Acceso a tu cuenta",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(14.dp))
        
        // Email Field
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                if (authState is AuthState.Error) viewModel.resetAuthState()
            },
            label = { Text(stringResource(R.string.email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            singleLine = true,
            isError = !emailIsValid,
            leadingIcon = {
                Icon(imageVector = Icons.Default.Email, contentDescription = null)
            },
            supportingText = {
                if (!emailIsValid) Text("Escribe un correo válido")
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Password Field
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                if (authState is AuthState.Error) viewModel.resetAuthState()
            },
            label = { Text(stringResource(R.string.password)) },
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            singleLine = true,
            leadingIcon = {
                Icon(imageVector = Icons.Default.Lock, contentDescription = null)
            },
            trailingIcon = {
                var image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(imageVector = image, contentDescription = null)
                }
            },
            supportingText = {
                Text("Mínimo 8 caracteres", style = MaterialTheme.typography.bodySmall)
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Confirm Password Field
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = {
                confirmPassword = it
                if (authState is AuthState.Error) viewModel.resetAuthState()
            },
            label = { Text("Confirmar contraseña") },
            visualTransformation = if (confirmPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            singleLine = true,
            leadingIcon = {
                Icon(imageVector = Icons.Default.Lock, contentDescription = null)
            },
            trailingIcon = {
                var image = if (confirmPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                    Icon(imageVector = image, contentDescription = null)
                }
            },
            isError = confirmPassword.isNotEmpty() && password != confirmPassword,
            supportingText = {
                if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                    Text(
                        "Las contraseñas no coinciden",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Sobre ti",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(14.dp))
        
        // Country Dropdown
        ExposedDropdownMenuBox(
            expanded = showCountryDropdown,
            onExpandedChange = { showCountryDropdown = it }
        ) {
            OutlinedTextField(
                value = country,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.country)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCountryDropdown)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                leadingIcon = {
                    Icon(imageVector = Icons.Default.LocationOn, contentDescription = null)
                }
            )
            
            ExposedDropdownMenu(
                expanded = showCountryDropdown,
                onDismissRequest = { showCountryDropdown = false }
            ) {
                countries.forEach { countryOption ->
                    DropdownMenuItem(
                        text = { Text(countryOption) },
                        onClick = {
                            country = countryOption
                            showCountryDropdown = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ExposedDropdownMenuBox(
            expanded = showGenderDropdown,
            onExpandedChange = { showGenderDropdown = it },
        ) {
            val genderLabel = when (gender) {
                "man" -> "Hombre"
                "woman" -> "Mujer"
                else -> "Prefiero no decirlo"
            }
            OutlinedTextField(
                value = genderLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Me identifico como") },
                leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = showGenderDropdown)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            )
            ExposedDropdownMenu(
                expanded = showGenderDropdown,
                onDismissRequest = { showGenderDropdown = false },
            ) {
                listOf(
                    "man" to "Hombre",
                    "woman" to "Mujer",
                    "unspecified" to "Prefiero no decirlo",
                ).forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            gender = value
                            showGenderDropdown = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = parsedBirthDate?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                ?: birthDate,
            onValueChange = {},
            readOnly = true,
            label = { Text("Fecha de nacimiento") },
            placeholder = { Text("Seleccionar fecha") },
            leadingIcon = { Icon(Icons.Default.Cake, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { showBirthDatePicker = true }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = "Elegir fecha")
                }
            },
            isError = birthDate.isNotBlank() && !birthDateIsValid,
            supportingText = {
                Text(
                    if (birthDate.isBlank() || birthDateIsValid) {
                        "Usaremos el día para avisar a tus amigos cuando sea tu cumpleaños."
                    } else {
                        "Selecciona una fecha válida."
                    }
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showBirthDatePicker = true },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = acceptedPrivacyPolicy,
                onCheckedChange = { acceptedPrivacyPolicy = it }
            )
            Text(
                text = "Acepto las políticas de privacidad",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { navController.navigate(Screen.PrivacyPolicy.route) }) {
                Text("Leer")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        
        // Register Button
        val validationMessage = when {
            name.isBlank() -> "Escribe tu nombre."
            !usernameAvailable -> "Comprueba que tu @usuario esté disponible."
            email.isBlank() -> "Escribe el correo que usarás para iniciar sesión."
            !emailIsValid -> "Escribe un correo válido."
            username.equals(email.trim().substringBefore('@'), ignoreCase = true) ->
                "Por privacidad, elige un @usuario distinto a la parte inicial de tu correo."
            password.length < 8 -> "La contraseña debe tener al menos 8 caracteres."
            confirmPassword.isBlank() -> "Confirma tu contraseña."
            password != confirmPassword -> "Las contraseñas no coinciden."
            country.isBlank() -> "Selecciona tu país."
            birthDate.isBlank() -> "Selecciona tu fecha de nacimiento."
            !birthDateIsValid -> "Selecciona una fecha de nacimiento válida."
            !acceptedPrivacyPolicy -> "Acepta las políticas de privacidad para crear tu cuenta."
            else -> null
        }
        Button(
            onClick = {
                if (validationMessage != null) {
                    AppFeedbackBus.showError(validationMessage)
                } else {
                    viewModel.signUpWithEmail(
                        email = email,
                        password = password,
                        name = name,
                        username = username,
                        country = country,
                        gender = gender,
                        birthDate = birthDate,
                        imageUri = profileImageUri,
                        acceptedPrivacyPolicy = acceptedPrivacyPolicy
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            enabled = authState !is AuthState.Loading
        ) {
            if (authState is AuthState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    text = stringResource(R.string.register),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Login Button
        TextButton(
            onClick = {
                navController.popBackStack()
            }
        ) {
            Text(
                text = stringResource(R.string.already_have_account) + " " + 
                       stringResource(R.string.login),
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        }
    }
}







