package conectate.com.presentation.profile

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.User
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.CommentAudioPlayer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal enum class ProfileEditSection(
    val title: String,
    val description: String,
) {
    APPEARANCE("Fotos y apariencia", "Foto de perfil, video de 2 segundos y portada"),
    BASIC("Información básica", "Nombre, biografía, ubicación y cumpleaños"),
    INTERESTS("Gustos y videojuegos", "Intereses, pasatiempos y juegos vinculados"),
    MUSIC("Música de entrada", "Canción que se reproduce desde la presentación de tu perfil"),
    SOCIAL("Redes sociales", "Instagram, TikTok, Facebook, X y WhatsApp"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileEditSections(
    section: ProfileEditSection?,
    user: User,
    isLoading: Boolean,
    errorMessage: String?,
    name: String,
    onNameChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    bio: String,
    onBioChange: (String) -> Unit,
    country: String,
    onCountryChange: (String) -> Unit,
    city: String,
    onCityChange: (String) -> Unit,
    gender: String,
    onGenderChange: (String) -> Unit,
    showGenderDropdown: Boolean,
    onGenderDropdownChange: (Boolean) -> Unit,
    education: String, onEducationChange: (String) -> Unit,
    workplace: String, onWorkplaceChange: (String) -> Unit,
    career: String, onCareerChange: (String) -> Unit,
    birthdayVisible: Boolean, onBirthdayVisibleChange: (Boolean) -> Unit,
    birthDate: String,
    birthDateIsValid: Boolean,
    onOpenBirthDate: () -> Unit,
    imageUri: Uri?,
    avatarMediaType: String?,
    avatarVideoMuted: Boolean,
    onAvatarVideoMutedChange: (Boolean) -> Unit,
    coverImageUri: Uri?,
    onPickAvatar: () -> Unit,
    onPickCover: () -> Unit,
    interestsText: String,
    onInterestsChange: (String) -> Unit,
    hobbiesText: String,
    onHobbiesChange: (String) -> Unit,
    linkedGamesText: String,
    onLinkedGamesChange: (String) -> Unit,
    profileMusicPreviewUrl: String?,
    profileMusicName: String?,
    profileMusicMarkedForRemoval: Boolean,
    onPickProfileMusic: () -> Unit,
    onRemoveProfileMusic: () -> Unit,
    instagram: String,
    onInstagramChange: (String) -> Unit,
    tiktok: String,
    onTikTokChange: (String) -> Unit,
    facebook: String,
    onFacebookChange: (String) -> Unit,
    xProfile: String,
    onXChange: (String) -> Unit,
    whatsapp: String,
    onWhatsAppChange: (String) -> Unit,
    onSelectSection: (ProfileEditSection) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (section) {
            null -> {
                Text(
                    "Elige una sección. Cada bloque se guarda por separado para que editar tu perfil sea más rápido.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProfileEditSection.entries.forEach { item ->
                    ProfileEditSectionRow(
                        section = item,
                        onClick = { onSelectSection(item) },
                    )
                }
            }

            ProfileEditSection.APPEARANCE -> {
                ProfileAppearanceEditor(
                    user = user,
                    imageUri = imageUri,
                    avatarMediaType = avatarMediaType,
                    avatarVideoMuted = avatarVideoMuted,
                    onAvatarVideoMutedChange = onAvatarVideoMutedChange,
                    coverImageUri = coverImageUri,
                    onPickAvatar = onPickAvatar,
                    onPickCover = onPickCover,
                )
                ProfileSaveArea(isLoading, errorMessage, onSave)
            }

            ProfileEditSection.BASIC -> {
                val canChangeName = remember(user.last_name_change) {
                    val lastChange = user.last_name_change ?: return@remember true
                    runCatching {
                        val lastDate = java.time.LocalDateTime.parse(lastChange)
                        java.time.Duration.between(lastDate, java.time.LocalDateTime.now()).toDays() >= 7
                    }.getOrDefault(true)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Nombre") },
                    enabled = canChangeName,
                    supportingText = { Text("${conectate.com.data.model.DisplayNamePolicy.length(name)}/20 · puedes cambiarlo cada 7 días") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Identificador único") },
                    prefix = { Text("@") },
                    supportingText = { Text("Es permanente. El nombre visible sí se puede cambiar.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { onBioChange(it.take(500)) },
                    label = { Text("Biografía") },
                    minLines = 3,
                    maxLines = 6,
                    supportingText = { Text("${bio.length}/500") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = country,
                    onValueChange = onCountryChange,
                    label = { Text("País") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = city,
                    onValueChange = onCityChange,
                    label = { Text("Ciudad") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = showGenderDropdown,
                    onExpandedChange = onGenderDropdownChange,
                ) {
                    OutlinedTextField(
                        value = genderLabel(gender),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Me identifico como") },
                        leadingIcon = { Icon(Icons.Default.Badge, null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showGenderDropdown) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = showGenderDropdown,
                        onDismissRequest = { onGenderDropdownChange(false) },
                    ) {
                        listOf(
                            "man" to "Hombre",
                            "woman" to "Mujer",
                            "unspecified" to "Prefiero no decirlo",
                        ).forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onGenderChange(value)
                                    onGenderDropdownChange(false)
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(value = education, onValueChange = onEducationChange,
                    label = { Text("Dónde estudiaste") }, placeholder = { Text("Colegio, instituto o universidad") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = career, onValueChange = onCareerChange,
                    label = { Text("Profesión o carrera") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = workplace, onValueChange = onWorkplaceChange,
                    label = { Text("Lugar de trabajo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val displayedBirthDate = remember(birthDate) {
                    runCatching { LocalDate.parse(birthDate) }
                        .getOrNull()
                        ?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        ?: birthDate
                }
                OutlinedTextField(
                    value = displayedBirthDate,
                    onValueChange = {},
                    readOnly = true,
                    isError = !birthDateIsValid,
                    label = { Text("Fecha de nacimiento") },
                    leadingIcon = { Icon(Icons.Default.Cake, null) },
                    trailingIcon = {
                        IconButton(onClick = onOpenBirthDate) {
                            Icon(Icons.Default.CalendarMonth, "Elegir fecha")
                        }
                    },
                    supportingText = { Text("Se usa para cumpleaños y controles de edad") },
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenBirthDate),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mostrar mi cumpleaños", style = MaterialTheme.typography.bodyLarge)
                        Text("Aparece al final de Presentación y permite avisar a tus amigos.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = birthdayVisible, onCheckedChange = onBirthdayVisibleChange)
                }
                ProfileSaveArea(isLoading, errorMessage, onSave)
            }

            ProfileEditSection.INTERESTS -> {
                Text(
                    "Separa cada elemento con una coma. Podrás cambiarlos cuando quieras.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = interestsText,
                    onValueChange = onInterestsChange,
                    label = { Text("Intereses") },
                    placeholder = { Text("Tecnología, fotografía, cine") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hobbiesText,
                    onValueChange = onHobbiesChange,
                    label = { Text("Pasatiempos") },
                    placeholder = { Text("Dibujar, leer, viajar") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = linkedGamesText,
                    onValueChange = onLinkedGamesChange,
                    label = { Text("Videojuegos") },
                    placeholder = { Text("Free Fire, Minecraft") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                ProfileSaveArea(isLoading, errorMessage, onSave)
            }

            ProfileEditSection.MUSIC -> {
                Text(
                    "Elige una canción de tu teléfono o del catálogo con licencia abierta. Podrás escucharla y recortar hasta 60 segundos antes de guardarla.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val playableUrl = profileMusicPreviewUrl
                    ?.takeUnless { profileMusicMarkedForRemoval }
                    ?.takeIf(String::isNotBlank)
                if (playableUrl == null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(38.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                if (profileMusicMarkedForRemoval) "La música se quitará al guardar" else "Aún no elegiste música",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                } else {
                    profileMusicName?.takeIf(String::isNotBlank)?.let { name ->
                        Text(name, style = MaterialTheme.typography.titleMedium)
                    }
                    CommentAudioPlayer(
                        audioUrl = playableUrl,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = onPickProfileMusic,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (playableUrl == null) "Elegir música" else "Cambiar música")
                }
                if (playableUrl != null) {
                    OutlinedButton(
                        onClick = onRemoveProfileMusic,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Quitar música")
                    }
                }
                ProfileSaveArea(isLoading, errorMessage, onSave)
            }

            ProfileEditSection.SOCIAL -> {
                Text(
                    "Pega enlaces HTTPS oficiales. WhatsApp se guarda con el número internacional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProfileSocialLinksEditor(
                    instagram = instagram,
                    onInstagramChange = onInstagramChange,
                    tiktok = tiktok,
                    onTikTokChange = onTikTokChange,
                    facebook = facebook,
                    onFacebookChange = onFacebookChange,
                    xProfile = xProfile,
                    onXChange = onXChange,
                    whatsapp = whatsapp,
                    onWhatsAppChange = onWhatsAppChange,
                )
                ProfileSaveArea(isLoading, errorMessage, onSave)
            }
        }
    }
}

@Composable
private fun ProfileEditSectionRow(
    section: ProfileEditSection,
    onClick: () -> Unit,
) {
    val icon = when (section) {
        ProfileEditSection.APPEARANCE -> Icons.Default.Palette
        ProfileEditSection.BASIC -> Icons.Default.Person
        ProfileEditSection.INTERESTS -> Icons.Default.SportsEsports
        ProfileEditSection.MUSIC -> Icons.Default.MusicNote
        ProfileEditSection.SOCIAL -> Icons.Default.Public
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.size(13.dp))
            Column(Modifier.weight(1f)) {
                Text(section.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    section.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, "Abrir ${section.title}")
        }
    }
}

@Composable
private fun ProfileAppearanceEditor(
    user: User,
    imageUri: Uri?,
    avatarMediaType: String?,
    avatarVideoMuted: Boolean,
    onAvatarVideoMutedChange: (Boolean) -> Unit,
    coverImageUri: Uri?,
    onPickAvatar: () -> Unit,
    onPickCover: () -> Unit,
) {
    val context = LocalContext.current
    val coverUrl = remember(user.cover_url) { UbuntuStorageUrl.rewrite(user.cover_url) }
    val coverKey = remember(user.cover_url) { UbuntuStorageUrl.stableCacheKey(user.cover_url) }
    val coverRequest = remember(coverUrl, coverKey) {
        coverUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCacheKey(coverKey)
                .diskCacheKey(coverKey)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
        }
    }
    Text("Portada", style = MaterialTheme.typography.titleMedium)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onPickCover),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = rememberAsyncImagePainter(coverImageUri ?: coverRequest),
            contentDescription = "Portada del perfil",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (coverImageUri == null && user.cover_url.isNullOrBlank()) {
            Text("Toca para agregar portada")
        }
        Button(
            onClick = onPickCover,
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) { Text("Cambiar") }
    }
    Text("Foto o video de perfil", style = MaterialTheme.typography.titleMedium)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProfileImage(openMomentOnClick = false, ownerId = user.id, 
            imageUrl = imageUri?.toString() ?: user.avatar_url,
            size = 112.dp,
            mediaType = avatarMediaType ?: user.avatar_media_type,
            muted = avatarVideoMuted,
            allowSound = true,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Los videos se repiten en un ciclo de hasta 2 segundos.")
            Button(
                onClick = onPickAvatar,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Cambiar") }
        }
    }
    if ((avatarMediaType ?: user.avatar_media_type)?.contains("video", true) == true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Reproducir avatar con sonido")
            Switch(
                checked = !avatarVideoMuted,
                onCheckedChange = { onAvatarVideoMutedChange(!it) },
            )
        }
    }
}

@Composable
private fun ProfileSaveArea(
    isLoading: Boolean,
    errorMessage: String?,
    onSave: () -> Unit,
) {
    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = onSave,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text("Guardar")
        }
    }
}

private fun genderLabel(value: String): String = when (value) {
    "man" -> "Hombre"
    "woman" -> "Mujer"
    else -> "Prefiero no decirlo"
}
