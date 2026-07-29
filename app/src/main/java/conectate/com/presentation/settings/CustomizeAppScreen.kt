package conectate.com.presentation.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.local.prefs.AppThemeOption
import conectate.com.presentation.components.AppAppearanceSettingsSection
import conectate.com.presentation.components.CommentAudioPlayer
import conectate.com.presentation.components.ProfileAmbientBackground
import conectate.com.presentation.profile.SettingsUiState
import conectate.com.presentation.profile.SettingsViewModel as ProfileSettingsViewModel
import conectate.com.presentation.theme.appThemePreviewColors
import conectate.com.presentation.theme.profileThemeColors

private data class ProfileEffectChoice(
    val id: String,
    val label: String,
    val icon: ImageVector
)

private fun commaSeparatedValues(raw: String): List<String> = raw
    .split(',', '\n')
    .map(String::trim)
    .filter(String::isNotBlank)

private fun gameLogoValues(raw: String): Map<String, String> = raw
    .lineSequence()
    .map { it.trim() }
    .filter { it.contains('=') }
    .mapNotNull { line ->
        val game = line.substringBefore('=').trim()
        val url = line.substringAfter('=').trim()
        if (game.isBlank() || url.isBlank()) null else game to url
    }
    .toMap()

private fun selectedProfileMusicName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }.getOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeAppScreen(
    navController: NavController,
    viewModel: ProfileSettingsViewModel = hiltViewModel()
) {
    val appTheme by viewModel.appTheme.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val fontOption by viewModel.fontOption.collectAsState()
    val textScale by viewModel.textScale.collectAsState()
    val settingsState by viewModel.settingsState.collectAsState()
    val appearanceSaving by viewModel.appearanceSaving.collectAsState()
    val appearanceMessage by viewModel.appearanceMessage.collectAsState()
    val profileMusicUploading by viewModel.profileMusicUploading.collectAsState()
    val profileMusicUploadProgress by viewModel.profileMusicUploadProgress.collectAsState()
    val profileMusicSelectedName by viewModel.profileMusicSelectedName.collectAsState()
    val user = (settingsState as? SettingsUiState.Success)?.user
    val context = LocalContext.current

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var profileTheme by rememberSaveable { mutableStateOf(AppThemeOption.CONECTATE) }
    var profileEffect by rememberSaveable { mutableStateOf("none") }
    var profileWelcome by rememberSaveable { mutableStateOf("") }
    var interestsText by rememberSaveable { mutableStateOf("") }
    var hobbiesText by rememberSaveable { mutableStateOf("") }
    var gamesText by rememberSaveable { mutableStateOf("") }
    var gameLogosText by rememberSaveable { mutableStateOf("") }
    var favoriteMusicUrl by rememberSaveable { mutableStateOf("") }
    var favoriteBook by rememberSaveable { mutableStateOf("") }
    var favoriteSport by rememberSaveable { mutableStateOf("") }
    var career by rememberSaveable { mutableStateOf("") }
    var availabilitySchedule by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val profileMusicPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadProfileMusic(
                uri = uri,
                displayName = selectedProfileMusicName(context, uri),
                onUploaded = { uploadedUrl -> favoriteMusicUrl = uploadedUrl }
            )
        }
    }

    LaunchedEffect(user?.id, user?.updated_at) {
        if (user != null) {
            profileTheme = AppThemeOption.fromStorage(user.profile_theme)
            profileEffect = user.profile_effect
            profileWelcome = user.profile_welcome.orEmpty()
            interestsText = user.interests.joinToString(", ")
            hobbiesText = user.hobbies.joinToString(", ")
            gamesText = user.linked_games.joinToString(", ")
            gameLogosText = user.game_logo_urls.entries.joinToString("\n") { (game, url) -> "$game=$url" }
            favoriteMusicUrl = user.favorite_music_url.orEmpty()
            favoriteBook = user.favorite_book.orEmpty()
            favoriteSport = user.favorite_sport.orEmpty()
            career = user.career.orEmpty()
            availabilitySchedule = user.availability_schedule.orEmpty()
        }
    }

    LaunchedEffect(appearanceMessage) {
        val message = appearanceMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearAppearanceMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ambiente") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Aplicacion") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Perfil") }
                )
            }

            if (selectedTab == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 128.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    item {
                        AppAppearanceSettingsSection(
                            isDarkMode = isDarkMode,
                            fontOption = fontOption,
                            textScale = textScale,
                            onDarkModeChanged = viewModel::setDarkMode,
                            onFontOptionChanged = viewModel::setFontOption,
                            onTextScaleChanged = viewModel::setTextScale,
                            showHeader = false
                        )
                    }
                    item { Divider() }
                    item {
                        SectionTitle("Temas (${AppThemeOption.entries.size})")
                        Spacer(modifier = Modifier.height(10.dp))
                        ThemeCatalog(
                            selected = appTheme,
                            dark = isDarkMode,
                            onSelected = viewModel::setAppTheme
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 128.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    item {
                        if (user == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
                            ProfileAppearancePreview(
                                displayName = user.displayName,
                                username = user.username.orEmpty(),
                                theme = profileTheme,
                                effect = profileEffect,
                                welcome = profileWelcome,
                                dark = isDarkMode
                            )
                        }
                    }
                    item {
                        SectionTitle("Tema del perfil")
                        Spacer(modifier = Modifier.height(10.dp))
                        ThemeCatalog(
                            selected = profileTheme,
                            dark = isDarkMode,
                            onSelected = { profileTheme = it }
                        )
                    }
                    item {
                        SectionTitle("Efecto")
                        Spacer(modifier = Modifier.height(8.dp))
                        ProfileEffectSelector(
                            selected = profileEffect,
                            onSelected = { profileEffect = it }
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = profileWelcome,
                            onValueChange = { profileWelcome = it.take(120) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Bienvenida") },
                            supportingText = { Text("${profileWelcome.length}/120") },
                            minLines = 2,
                            maxLines = 3
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle("Tu algoritmo")
                            OutlinedTextField(
                                value = interestsText,
                                onValueChange = { interestsText = it.take(1200) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Intereses") },
                                placeholder = { Text("tecnología, fotografía, viajes") },
                                minLines = 2,
                                maxLines = 4
                            )
                            OutlinedTextField(
                                value = hobbiesText,
                                onValueChange = { hobbiesText = it.take(1200) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Hobbies") },
                                placeholder = { Text("dibujo, lectura, cocina") },
                                minLines = 2,
                                maxLines = 4
                            )
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle("Videojuegos")
                            OutlinedTextField(
                                value = gamesText,
                                onValueChange = { gamesText = it.take(800) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Juegos") },
                                placeholder = { Text("Minecraft, Free Fire, Fortnite") },
                                minLines = 2,
                                maxLines = 4
                            )
                            OutlinedTextField(
                                value = gameLogosText,
                                onValueChange = { gameLogosText = it.take(4000) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Logos de videojuegos") },
                                placeholder = { Text("Minecraft=https://...") },
                                minLines = 2,
                                maxLines = 6
                            )
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle("Información personal")
                            OutlinedTextField(
                                value = career,
                                onValueChange = { career = it.take(180) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Profesión o carrera") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = favoriteSport,
                                onValueChange = { favoriteSport = it.take(160) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Deporte") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = favoriteBook,
                                onValueChange = { favoriteBook = it.take(160) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Libro") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = availabilitySchedule,
                                onValueChange = { availabilitySchedule = it.take(280) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Disponibilidad") },
                                minLines = 2,
                                maxLines = 3
                            )
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle("Música del perfil")
                            OutlinedTextField(
                                value = favoriteMusicUrl,
                                onValueChange = { favoriteMusicUrl = it.take(700) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Enlace de música") },
                                placeholder = { Text("YouTube o enlace directo") },
                                singleLine = true
                            )
                            OutlinedButton(
                                onClick = { profileMusicPicker.launch("audio/*") },
                                enabled = user != null && !profileMusicUploading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (profileMusicUploading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.MusicNote, contentDescription = null)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (profileMusicUploading) {
                                        "Subiendo canción…"
                                    } else {
                                        "Elegir canción del dispositivo"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (profileMusicUploading) {
                                LinearProgressIndicator(
                                    progress = profileMusicUploadProgress.coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            profileMusicSelectedName?.let { fileName ->
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "Archivos de hasta 40 MB. La canción elegida se guarda automáticamente; los enlaces se guardan con el botón inferior.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (favoriteMusicUrl.isNotBlank()) {
                                CommentAudioPlayer(
                                    audioUrl = favoriteMusicUrl,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                viewModel.saveProfileCustomization(
                                    theme = profileTheme,
                                    effect = profileEffect,
                                    welcome = profileWelcome,
                                    interests = commaSeparatedValues(interestsText),
                                    hobbies = commaSeparatedValues(hobbiesText),
                                    linkedGames = commaSeparatedValues(gamesText),
                                    gameLogoUrls = gameLogoValues(gameLogosText),
                                    favoriteMusicUrl = favoriteMusicUrl,
                                    favoriteBook = favoriteBook,
                                    favoriteSport = favoriteSport,
                                    career = career,
                                    availabilitySchedule = availabilitySchedule
                                )
                            },
                            enabled = user != null && !appearanceSaving,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (appearanceSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.Save, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Guardar perfil")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ThemeCatalog(
    selected: AppThemeOption,
    dark: Boolean,
    onSelected: (AppThemeOption) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppThemeOption.entries.chunked(2).forEach { rowThemes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowThemes.forEach { theme ->
                    ThemeOption(
                        theme = theme,
                        selected = selected == theme,
                        dark = dark,
                        onClick = { onSelected(theme) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(2 - rowThemes.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeOption(
    theme: AppThemeOption,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previewColors = appThemePreviewColors(theme, dark)
    Surface(
        onClick = onClick,
        modifier = modifier.height(92.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    previewColors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(24.dp)
                                .background(color)
                        )
                    }
                }
                Text(
                    text = theme.label,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Seleccionado",
                        modifier = Modifier
                            .padding(3.dp)
                            .size(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEffectSelector(
    selected: String,
    onSelected: (String) -> Unit
) {
    val effects = remember {
        listOf(
            ProfileEffectChoice("none", "Limpio", Icons.Default.Person),
            ProfileEffectChoice("sparkles", "Destellos", Icons.Default.AutoAwesome),
            ProfileEffectChoice("constellation", "Constelacion", Icons.Default.Share),
            ProfileEffectChoice("confetti", "Confeti", Icons.Default.ConfirmationNumber),
            ProfileEffectChoice("waves", "Ondas", Icons.Default.Share),
            ProfileEffectChoice("orbit", "Orbita", Icons.Default.AutoAwesome),
            ProfileEffectChoice("rain", "Lluvia", Icons.Default.ConfirmationNumber),
            ProfileEffectChoice("equalizer", "Ritmo", Icons.Default.AutoAwesome)
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        effects.chunked(2).forEach { rowEffects ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowEffects.forEach { effect ->
                    FilterChip(
                        selected = selected == effect.id,
                        onClick = { onSelected(effect.id) },
                        label = { Text(effect.label, maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                effect.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileAppearancePreview(
    displayName: String,
    username: String,
    theme: AppThemeOption,
    effect: String,
    welcome: String,
    dark: Boolean
) {
    val colors = profileThemeColors(theme.storageValue, dark)
    ProfileAmbientBackground(
        themeId = theme.storageValue,
        effectId = effect,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = colors.accent,
                    contentColor = colors.accentForeground
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.padding(11.dp),
                        tint = colors.accentForeground
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        color = colors.foreground,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${username.ifBlank { "usuario" }}",
                        color = colors.foreground.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = welcome.ifBlank { "Bienvenido a mi perfil" },
                color = colors.foreground,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
