package conectate.com.presentation.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import conectate.com.presentation.components.CircularProfileImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val profileState by viewModel.profileState.collectAsState()
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // State for fields
    var name by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var interestsText by remember { mutableStateOf("") }
    var gamesText by remember { mutableStateOf("") }
    var favoriteMusicUrl by remember { mutableStateOf("") }
    var favoriteBook by remember { mutableStateOf("") }
    var favoriteSport by remember { mutableStateOf("") }
    var career by remember { mutableStateOf("") }
    var availabilitySchedule by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var coverImageUri by remember { mutableStateOf<Uri?>(null) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(profileState) {
        if (!initialized && profileState is ProfileUiState.Success) {
            val user = (profileState as ProfileUiState.Success).user
            name = user.displayName
            bio = user.bio ?: ""
            country = user.country ?: ""
            city = user.city ?: ""
            status = user.status ?: ""
            interestsText = user.interests.joinToString(", ")
            gamesText = formatGamesWithLogos(user.linked_games, user.game_logo_urls)
            favoriteMusicUrl = user.favorite_music_url ?: ""
            favoriteBook = user.favorite_book ?: ""
            favoriteSport = user.favorite_sport ?: ""
            career = user.career ?: ""
            availabilitySchedule = user.availability_schedule ?: ""
            initialized = true
        }
    }
    
    // Load profile on screen entry (in case ViewModel was just created)
    LaunchedEffect(Unit) {
        viewModel.loadUserProfile(null)
    }

    val profileImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        imageUri = uri
    }
    
    val coverImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        coverImageUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editar Perfil") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
             when (val state = profileState) {
                is ProfileUiState.Success -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Cover Photo Section
                        Text(
                            "Foto de Portada",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            // Cover image preview
                            androidx.compose.foundation.Image(
                                painter = if (coverImageUri != null) {
                                    coil.compose.rememberAsyncImagePainter(coverImageUri)
                                } else if (!state.user.cover_url.isNullOrEmpty()) {
                                    coil.compose.rememberAsyncImagePainter(state.user.cover_url)
                                } else {
                                    coil.compose.rememberAsyncImagePainter(null)
                                },
                                contentDescription = "Portada",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                    .clickable { coverImageLauncher.launch("image/*") },
                                contentScale = ContentScale.Crop
                            )
                            
                            // Overlay for empty state
                            if (coverImageUri == null && state.user.cover_url.isNullOrEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                        .clickable { coverImageLauncher.launch("image/*") }
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Toca para agregar portada",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            
                            // Edit button
                            Button(
                                onClick = { coverImageLauncher.launch("image/*") },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                            ) {
                                Text("Cambiar")
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Profile Photo Section
                        Text(
                            "Foto de Perfil",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        
                        // Profile Image with Edit Overlay
                        Box(contentAlignment = Alignment.BottomEnd) {
                             CircularProfileImage(
                                imageUrl = imageUri?.toString() ?: state.user.avatar_url,
                                size = 120.dp
                            )
                            Button(
                                onClick = { profileImageLauncher.launch("image/*") },
                                modifier = Modifier.offset(y = 10.dp)
                            ) {
                                Text("Cambiar")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val canChangeName by remember(state) {
                            derivedStateOf {
                                val lastChange = state.user.last_name_change
                                if (lastChange == null) true
                                else {
                                    try {
                                        // Simple Parsing or proper DateTimeformatter
                                        // Assuming ISO-8601
                                        val lastDate = java.time.LocalDateTime.parse(lastChange)
                                        val diff = java.time.Duration.between(lastDate, java.time.LocalDateTime.now()).toDays()
                                        diff >= 7
                                    } catch (e: Exception) { true }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Nombre Completo") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canChangeName,
                            supportingText = { 
                                if (!canChangeName) {
                                    Text("Solo puedes cambiar esto cada 7 días", color = MaterialTheme.colorScheme.error)
                                } else {
                                    Text("Solo puedes cambiar esto cada 7 días") 
                                }
                            }
                        )

                        OutlinedTextField(
                            value = bio,
                            onValueChange = { if (it.length <= 500) bio = it }, // Hard limit input
                            label = { Text("Biografía") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 5,
                            supportingText = {
                                Text("${bio.length}/500 caracteres", 
                                    color = if (bio.length >= 500) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )

                        OutlinedTextField(
                            value = country,
                            onValueChange = { country = it },
                            label = { Text("País") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it },
                            label = { Text("Ciudad") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Divider()

                        Text(
                            "Perfil estilo Discord",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Start)
                        )

                        OutlinedTextField(
                            value = status,
                            onValueChange = { if (it.length <= 80) status = it },
                            label = { Text("Estado o frase personal") },
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("${status.length}/80 caracteres") }
                        )

                        OutlinedTextField(
                            value = interestsText,
                            onValueChange = { interestsText = it },
                            label = { Text("Intereses") },
                            placeholder = { Text("anime, juegos, música, libros, deportes") },
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("Separa cada interés con coma.") }
                        )

                        OutlinedTextField(
                            value = gamesText,
                            onValueChange = { gamesText = it },
                            label = { Text("Juegos vinculados") },
                            placeholder = { Text("Minecraft|https://logo.png, Valorant") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            supportingText = { Text("Usa Nombre|URL para guardar logo opcional.") }
                        )

                        OutlinedTextField(
                            value = favoriteMusicUrl,
                            onValueChange = { favoriteMusicUrl = it.trim().take(300) },
                            label = { Text("Música favorita (Spotify/Apple Music)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = favoriteBook,
                            onValueChange = { if (it.length <= 120) favoriteBook = it },
                            label = { Text("Libro favorito") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = favoriteSport,
                            onValueChange = { if (it.length <= 80) favoriteSport = it },
                            label = { Text("Deporte favorito") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = career,
                            onValueChange = { if (it.length <= 120) career = it },
                            label = { Text("Carrera / ocupación") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = availabilitySchedule,
                            onValueChange = { if (it.length <= 160) availabilitySchedule = it },
                            label = { Text("Horario") },
                            placeholder = { Text("Lun-Vie 18:00-22:00") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (errorMessage != null) {
                            Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                        }

                        Button(
                            onClick = {
                                val trimmedName = name.trim()
                                val trimmedBio = bio.trim()
                                
                                when {
                                    trimmedName.isEmpty() -> errorMessage = "El nombre no puede estar vacío"
                                    trimmedName.length < 2 -> errorMessage = "El nombre es muy corto"
                                    trimmedName.length > 50 -> errorMessage = "El nombre es muy largo"
                                    else -> {
                                        isLoading = true
                                        errorMessage = null
                                        val parsedGames = parseGamesWithLogos(gamesText)
                                        viewModel.updateProfile(
                                            name = trimmedName,
                                            bio = trimmedBio,
                                            country = country.trim(),
                                            city = city.trim(),
                                            imageUri = imageUri,
                                            coverImageUri = coverImageUri,
                                            status = status,
                                            interests = parseCommaList(interestsText),
                                            linkedGames = parsedGames.first,
                                            gameLogoUrls = parsedGames.second,
                                            favoriteMusicUrl = favoriteMusicUrl,
                                            favoriteBook = favoriteBook,
                                            favoriteSport = favoriteSport,
                                            career = career,
                                            availabilitySchedule = availabilitySchedule,
                                            onSuccess = {
                                                isLoading = false
                                                // Force reload to show changes immediately
                                                viewModel.loadUserProfile(null)
                                                android.widget.Toast.makeText(
                                                    context, 
                                                    "¡Cambios guardados exitosamente!", 
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                navController.popBackStack()
                                            },
                                            onError = {
                                                isLoading = false
                                                errorMessage = it
                                            }
                                        )
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                             if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Guardar Cambios")
                            }
                        }
                    }
                }
                is ProfileUiState.Error -> {
                     Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Error al cargar perfil")
                        Button(onClick = { /* retry? */ }) { Text("Reintentar") }
                    }
                }
                ProfileUiState.Loading -> {
                     Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

private fun parseCommaList(value: String): List<String> {
    return value
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun parseGamesWithLogos(value: String): Pair<List<String>, Map<String, String>> {
    val games = mutableListOf<String>()
    val logos = linkedMapOf<String, String>()

    value.split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { raw ->
            val parts = raw.split('|', limit = 2).map { it.trim() }
            val name = parts.firstOrNull().orEmpty()
            if (name.isNotBlank()) {
                games += name
                parts.getOrNull(1)
                    ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                    ?.let { logos[name] = it }
            }
        }

    return games.distinct() to logos
}

private fun formatGamesWithLogos(games: List<String>, logos: Map<String, String>): String {
    return games.joinToString(", ") { game ->
        val logo = logos[game]
        if (logo.isNullOrBlank()) game else "$game|$logo"
    }
}
