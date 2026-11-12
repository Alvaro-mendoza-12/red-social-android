package conectate.com.presentation.profile
import conectate.com.core.media.profileAvatarPreviewItem
import conectate.com.core.media.useSingleProfilePlayback

import androidx.compose.runtime.Composable

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.res.stringResource
import conectate.com.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.core.profile.ProfileSocialLinks
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.DeviceAudioPickerDialog
import conectate.com.presentation.components.ImageCropPreset
import conectate.com.presentation.components.PreparedAudioClip
import conectate.com.presentation.components.SimpleImageCropDialog
import conectate.com.presentation.components.SocialBrandLogo
import conectate.com.presentation.components.SocialPlatform
import conectate.com.presentation.messages.MessageMediaComposer
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val PROFILE_AVATAR_MAX_VIDEO_MS = 2_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val profileState by viewModel.profileState.collectAsState()
    val draftAccountId = viewModel.currentUserId
    key(draftAccountId) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // State for fields
    var name by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var username by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var bio by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var country by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var city by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var gender by rememberSaveable(draftAccountId) { mutableStateOf("unspecified") }
    var birthDate by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var education by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var workplace by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var career by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var birthdayVisible by rememberSaveable(draftAccountId) { mutableStateOf(true) }
    var showGenderDropdown by remember { mutableStateOf(false) }
    var showBirthDatePicker by remember { mutableStateOf(false) }
    var imageUri by rememberSaveable(draftAccountId) { mutableStateOf<Uri?>(null) }
    var avatarMediaType by rememberSaveable(draftAccountId) { mutableStateOf<String?>(null) }
    var avatarDurationMs by rememberSaveable(draftAccountId) { mutableStateOf<Long?>(null) }
    var avatarVideoMuted by rememberSaveable(draftAccountId) { mutableStateOf(true) }
    var coverImageUri by rememberSaveable(draftAccountId) { mutableStateOf<Uri?>(null) }
    var trimCandidateUri by remember { mutableStateOf<Uri?>(null) }
    var trimCandidateDurationMs by remember { mutableStateOf(0L) }
    var pendingImageCrop by remember { mutableStateOf<Pair<Uri, ProfileImageTarget>?>(null) }
    var initialized by rememberSaveable(draftAccountId) { mutableStateOf(false) }
    var instagram by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var tiktok by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var facebook by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var xProfile by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var whatsapp by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var interestsText by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var hobbiesText by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var linkedGamesText by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var favoriteMusicUrl by rememberSaveable(draftAccountId) { mutableStateOf("") }
    var pendingProfileMusic by rememberSaveable(draftAccountId, stateSaver = ProfileMusicDraftSaver) { mutableStateOf<PreparedAudioClip?>(null) }
    var profileMusicMarkedForRemoval by rememberSaveable(draftAccountId) { mutableStateOf(false) }
    var showProfileMusicPicker by remember { mutableStateOf(false) }
    var profileMusicPickerInitialUri by remember { mutableStateOf<Uri?>(null) }
    var selectedSection by rememberSaveable(draftAccountId) { mutableStateOf<ProfileEditSection?>(null) }

    var isTrimmingAvatar by remember { mutableStateOf(false) }

    var showUnifiedMediaPicker by remember { mutableStateOf(false) }
    var unifiedPickerTarget by remember { mutableStateOf(ProfileImageTarget.AVATAR) }
    val profileMusicFileLauncher = conectate.com.presentation.documents.rememberInAppDocumentPicker() { uri ->
        if (uri != null) {
            profileMusicPickerInitialUri = uri
            showProfileMusicPicker = true
        }
    }

    var showDiscardDialog by rememberSaveable(draftAccountId) { mutableStateOf(false) }

    LaunchedEffect(profileState) {
        if (!initialized && profileState is ProfileUiState.Success) {
            val user = (profileState as ProfileUiState.Success).user
            if (!user.id.equals(draftAccountId, ignoreCase = true)) return@LaunchedEffect
            name = user.displayName
            username = user.username.orEmpty()
            bio = user.bio ?: ""
            country = user.country ?: ""
            city = user.city ?: ""
            gender = user.gender
            birthDate = user.birth_date.orEmpty()
            education = user.education.orEmpty()
            workplace = user.workplace.orEmpty()
            career = user.career.orEmpty()
            birthdayVisible = user.profile_section_visibility["birthday"] != "only_me"
            avatarMediaType = user.avatar_media_type
            avatarDurationMs = user.avatar_duration_ms
            avatarVideoMuted = user.avatar_video_muted
            instagram = user.social_links["instagram"].orEmpty()
            tiktok = user.social_links["tiktok"].orEmpty()
            facebook = user.social_links["facebook"].orEmpty()
            xProfile = user.social_links["x"].orEmpty()
            whatsapp = user.social_links["whatsapp"].orEmpty()
            interestsText = user.interests.joinToString(", ")
            hobbiesText = user.hobbies.joinToString(", ")
            linkedGamesText = user.linked_games.joinToString(", ")
            favoriteMusicUrl = user.favorite_music_url.orEmpty()
            initialized = true
        }
    }
    
    // Load profile on screen entry (in case ViewModel was just created)
    LaunchedEffect(Unit) {
        viewModel.loadUserProfile(null)
    }

    val parsedBirthDate = remember(birthDate) {
        runCatching { LocalDate.parse(birthDate) }.getOrNull()
    }
    val birthDateIsValid = birthDate.isBlank() ||
        parsedBirthDate?.let { !it.isAfter(LocalDate.now()) } == true

    if (showBirthDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = parsedBirthDate
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(LocalDate.now())
                override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now().year
            },
        )
        DatePickerDialog(
            onDismissRequest = { showBirthDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis?.let { datePickerState.selectableDates.isSelectableDate(it) } == true,
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selected = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                            if (!selected.isAfter(LocalDate.now())) {
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
            Column {
                Text(stringResource(R.string.audit_birthdate_hint), modifier = Modifier.padding(horizontal = 24.dp))
                DatePicker(state = datePickerState)
            }
        }
    }

    pendingImageCrop?.let { (sourceUri, target) ->
        val preset = if (target == ProfileImageTarget.AVATAR) {
            ImageCropPreset.SQUARE
        } else {
            ImageCropPreset.COVER
        }
        SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = if (target == ProfileImageTarget.AVATAR) {
                "Recortar foto de perfil"
            } else {
                "Recortar portada"
            },
            presets = listOf(preset),
            initialPreset = preset,
            onDismiss = { pendingImageCrop = null },
            onCropped = { croppedUri ->
                if (target == ProfileImageTarget.AVATAR) {
                    imageUri = croppedUri
                    avatarMediaType = "image"
                    avatarDurationMs = null
                    avatarVideoMuted = true
                } else {
                    coverImageUri = croppedUri
                }
                pendingImageCrop = null
            }
        )
    }

    trimCandidateUri?.let { candidateUri ->
        ProfileAvatarTrimSheet(
            uri = candidateUri,
            durationMs = trimCandidateDurationMs,
            isTrimming = isTrimmingAvatar,
            onDismiss = {
                if (!isTrimmingAvatar) {
                    trimCandidateUri = null
                    trimCandidateDurationMs = 0L
                }
            },
            onTrim = { startMs ->
                isTrimmingAvatar = true
                viewModel.trimProfileAvatarVideo(
                    context = context,
                    inputUri = candidateUri,
                    startMs = startMs,
                    onSuccess = { trimmedUri ->
                        isTrimmingAvatar = false
                        trimCandidateUri = null
                        trimCandidateDurationMs = 0L
                        imageUri = trimmedUri
                        avatarMediaType = "video"
                        avatarDurationMs = PROFILE_AVATAR_MAX_VIDEO_MS
                        avatarVideoMuted = true
                    },
                    onError = { message ->
                        isTrimmingAvatar = false
                        errorMessage = message
                    }
                )
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {

    val savedUser = (profileState as? ProfileUiState.Success)?.user?.takeIf { it.id.equals(draftAccountId, ignoreCase = true) }
    val hasUnsavedChanges = initialized && savedUser != null && (
        name != savedUser.displayName || username != savedUser.username.orEmpty() ||
        bio != savedUser.bio.orEmpty() || country != savedUser.country.orEmpty() || city != savedUser.city.orEmpty() ||
        gender != savedUser.gender || birthDate != savedUser.birth_date.orEmpty() ||
        education != savedUser.education.orEmpty() || workplace != savedUser.workplace.orEmpty() || career != savedUser.career.orEmpty() ||
        birthdayVisible != (savedUser.profile_section_visibility["birthday"] != "only_me") ||
        imageUri != null || coverImageUri != null || avatarVideoMuted != savedUser.avatar_video_muted ||
        parseProfileList(interestsText) != savedUser.interests || parseProfileList(hobbiesText) != savedUser.hobbies ||
        parseProfileList(linkedGamesText) != savedUser.linked_games ||
        profileSocialDraftHasChanges(
            draft = mapOf("instagram" to instagram, "tiktok" to tiktok, "facebook" to facebook, "x" to xProfile, "whatsapp" to whatsapp),
            saved = savedUser.social_links,
        ) || pendingProfileMusic != null || profileMusicMarkedForRemoval
    )
    val onBack: () -> Unit = {
        if (!isLoading && !isTrimmingAvatar) {
            if (selectedSection != null) selectedSection = null
            else if (hasUnsavedChanges) showDiscardDialog = true
            else navController.popBackStack()
        }
    }
    BackHandler(onBack = onBack)
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.audit_discard_title)) },
            text = { Text(stringResource(R.string.audit_discard_profile_message)) },
            confirmButton = { TextButton(onClick = { showDiscardDialog = false; navController.popBackStack() }) {
                Text(stringResource(R.string.audit_discard))
            } },
            dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text(stringResource(R.string.audit_keep_editing)) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        selectedSection?.title ?: "Editar perfil",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
             when (val state = profileState) {
                is ProfileUiState.Success -> {
                    if (!state.user.id.equals(draftAccountId, ignoreCase = true)) {
                        CircularProgressIndicator()
                    } else {
                    val currentSection = selectedSection
                    ProfileEditSections(
                        section = currentSection,
                        user = state.user,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        name = name,
                        onNameChange = { name = conectate.com.data.model.DisplayNamePolicy.input(it) },
                        username = username,
                        onUsernameChange = { username = it.removePrefix("@").take(24) },
                        bio = bio,
                        onBioChange = { bio = it },
                        country = country,
                        onCountryChange = { country = it.take(80) },
                        city = city,
                        onCityChange = { city = it.take(80) },
                        gender = gender,
                        onGenderChange = { gender = it },
                        showGenderDropdown = showGenderDropdown,
                        onGenderDropdownChange = { showGenderDropdown = it },
                        education = education, onEducationChange = { education = it.take(180) },
                        workplace = workplace, onWorkplaceChange = { workplace = it.take(180) },
                        career = career, onCareerChange = { career = it.take(180) },
                        birthdayVisible = birthdayVisible, onBirthdayVisibleChange = { birthdayVisible = it },
                        birthDate = birthDate,
                        birthDateIsValid = birthDateIsValid,
                        onOpenBirthDate = { showBirthDatePicker = true },
                        imageUri = imageUri,
                        avatarMediaType = avatarMediaType,
                        avatarVideoMuted = avatarVideoMuted,
                        onAvatarVideoMutedChange = { avatarVideoMuted = it },
                        coverImageUri = coverImageUri,
                        onPickAvatar = {
                            unifiedPickerTarget = ProfileImageTarget.AVATAR
                            showUnifiedMediaPicker = true
                        },
                        onPickCover = {
                            unifiedPickerTarget = ProfileImageTarget.COVER
                            showUnifiedMediaPicker = true
                        },
                        interestsText = interestsText,
                        onInterestsChange = { interestsText = it.take(400) },
                        hobbiesText = hobbiesText,
                        onHobbiesChange = { hobbiesText = it.take(400) },
                        linkedGamesText = linkedGamesText,
                        onLinkedGamesChange = { linkedGamesText = it.take(400) },
                        profileMusicPreviewUrl = pendingProfileMusic?.uri?.toString()
                            ?: favoriteMusicUrl.takeIf(String::isNotBlank),
                        profileMusicName = pendingProfileMusic?.displayName
                            ?: favoriteMusicUrl.takeIf(String::isNotBlank)?.let { "Canción actual" },
                        profileMusicMarkedForRemoval = profileMusicMarkedForRemoval,
                        onPickProfileMusic = {
                            profileMusicPickerInitialUri = null
                            showProfileMusicPicker = true
                        },
                        onRemoveProfileMusic = {
                            pendingProfileMusic = null
                            profileMusicMarkedForRemoval = true
                        },
                        instagram = instagram,
                        onInstagramChange = { instagram = it.take(240) },
                        tiktok = tiktok,
                        onTikTokChange = { tiktok = it.take(240) },
                        facebook = facebook,
                        onFacebookChange = { facebook = it.take(240) },
                        xProfile = xProfile,
                        onXChange = { xProfile = it.take(240) },
                        whatsapp = whatsapp,
                        onWhatsAppChange = { whatsapp = it.take(24) },
                        onSelectSection = {
                            errorMessage = null
                            selectedSection = it
                        },
                        onSave = {
                            val trimmedName = name.trim()
                            val trimmedUsername = username.trim()
                            val usernameChanged = currentSection == ProfileEditSection.BASIC &&
                                trimmedUsername != state.user.username.orEmpty().trim()
                            val trimmedBio = bio.trim()
                            when {
                                currentSection == null -> Unit
                                currentSection == ProfileEditSection.MUSIC -> {
                                    isLoading = true
                                    errorMessage = null
                                    viewModel.saveProfileMusic(
                                        clip = pendingProfileMusic,
                                        removeMusic = profileMusicMarkedForRemoval,
                                        onSuccess = { savedUrl ->
                                            isLoading = false
                                            favoriteMusicUrl = savedUrl.orEmpty()
                                            pendingProfileMusic = null
                                            profileMusicMarkedForRemoval = false
                                            selectedSection = null
                                            android.widget.Toast.makeText(
                                                context,
                                                if (savedUrl.isNullOrBlank()) "Música quitada" else "Música guardada",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                        onError = {
                                            isLoading = false
                                            errorMessage = it
                                        },
                                    )
                                }
                                currentSection == ProfileEditSection.BASIC && trimmedName != state.user.displayName.trim() && !conectate.com.data.model.DisplayNamePolicy.valid(trimmedName) ->
                                    errorMessage = "El nombre debe tener entre 2 y 20 caracteres"
                                currentSection == ProfileEditSection.BASIC && usernameChanged && !Regex("[A-Za-z0-9_.-]{3,24}").matches(trimmedUsername) ->
                                    errorMessage = "El usuario debe tener 3–24 letras, números, _, . o -."
                                currentSection == ProfileEditSection.BASIC && !birthDateIsValid ->
                                    errorMessage = "Selecciona una fecha de nacimiento válida"
                                currentSection == ProfileEditSection.SOCIAL &&
                                    !socialLinksAreValid(instagram, tiktok, facebook, xProfile, whatsapp) ->
                                    errorMessage = "Usa enlaces HTTPS oficiales y un número internacional válido"
                                else -> {
                                    isLoading = true
                                    errorMessage = null
                                    viewModel.updateProfile(
                                        name = trimmedName.takeIf { currentSection == ProfileEditSection.BASIC },
                                        username = trimmedUsername.takeIf { currentSection == ProfileEditSection.BASIC && usernameChanged },
                                        bio = trimmedBio.takeIf { currentSection == ProfileEditSection.BASIC },
                                        country = country.trim().takeIf { currentSection == ProfileEditSection.BASIC },
                                        city = city.trim().takeIf { currentSection == ProfileEditSection.BASIC },
                                        gender = gender.takeIf { currentSection == ProfileEditSection.BASIC },
                                        birthDate = birthDate.takeIf { currentSection == ProfileEditSection.BASIC && it.isNotBlank() },
                                        education = education.takeIf { currentSection == ProfileEditSection.BASIC },
                                        workplace = workplace.takeIf { currentSection == ProfileEditSection.BASIC },
                                        career = career.takeIf { currentSection == ProfileEditSection.BASIC },
                                        sectionVisibility = (state.user.profile_section_visibility + ("birthday" to if (birthdayVisible) state.user.profile_section_visibility["birthday"]?.takeIf { it == "followers" } ?: "everyone" else "only_me")).takeIf { currentSection == ProfileEditSection.BASIC },
                                        imageUri = imageUri.takeIf {
                                            currentSection == ProfileEditSection.APPEARANCE
                                        },
                                        avatarMediaType = avatarMediaType.takeIf { currentSection == ProfileEditSection.APPEARANCE },
                                        avatarVideoMuted = avatarVideoMuted.takeIf { currentSection == ProfileEditSection.APPEARANCE },
                                        avatarDurationMs = avatarDurationMs.takeIf { currentSection == ProfileEditSection.APPEARANCE },
                                        coverImageUri = coverImageUri.takeIf {
                                            currentSection == ProfileEditSection.APPEARANCE
                                        },
                                        interests = parseProfileList(interestsText).takeIf {
                                            currentSection == ProfileEditSection.INTERESTS
                                        },
                                        hobbies = parseProfileList(hobbiesText).takeIf {
                                            currentSection == ProfileEditSection.INTERESTS
                                        },
                                        linkedGames = parseProfileList(linkedGamesText).takeIf {
                                            currentSection == ProfileEditSection.INTERESTS
                                        },
                                        socialLinks = normalizedSocialLinks(
                                            instagram = instagram,
                                            tiktok = tiktok,
                                            facebook = facebook,
                                            xProfile = xProfile,
                                            whatsapp = whatsapp,
                                        ).takeIf {
                                            currentSection == ProfileEditSection.SOCIAL
                                        },
                                        onSuccess = {
                                            isLoading = false
                                            if (currentSection == ProfileEditSection.APPEARANCE) {
                                                imageUri = null
                                                coverImageUri = null
                                            }
                                            if (currentSection == ProfileEditSection.BASIC) {
                                                name = trimmedName
                                                username = trimmedUsername
                                                bio = trimmedBio
                                                country = country.trim()
                                                city = city.trim()
                                            }
                                            selectedSection = null
                                            android.widget.Toast.makeText(
                                                context,
                                                "Cambios guardados",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                        onError = {
                                            isLoading = false
                                            errorMessage = it
                                        },
                                    )
                                }
                            }
                        },
                    )
                    }
                }
                is ProfileUiState.Error -> {
                     Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Error al cargar perfil")
                        Button(onClick = { viewModel.loadUserProfile(userId = null, forceNetwork = true) }) {
                            Text("Reintentar")
                        }
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

    if (showUnifiedMediaPicker) {
        MessageMediaComposer(
            initialFilter = if (unifiedPickerTarget == ProfileImageTarget.COVER) "image" else "all",
            maxItems = 1,
            allowedMediaTypes = if (unifiedPickerTarget == ProfileImageTarget.COVER) {
                setOf("image")
            } else {
                setOf("image", "video")
            },
            title = if (unifiedPickerTarget == ProfileImageTarget.COVER) {
                "Elegir foto de portada"
            } else {
                "Elegir foto o video de perfil"
            },
            confirmContentDescription = "Usar en el perfil",
            showViewOnceOption = false,
            showCaptionInput = false,
            isSending = false,
            onDismiss = { showUnifiedMediaPicker = false },
            onSend = { selected, _ ->
                showUnifiedMediaPicker = false
                selected.firstOrNull()?.let { media ->
                    val uri = media.uri
                    if (unifiedPickerTarget == ProfileImageTarget.AVATAR) {
                        val mimeType = context.contentResolver.getType(uri)?.substringBefore(";")?.lowercase().orEmpty()
                        val uriText = uri.toString().substringBefore("?").lowercase()
                        val isVideo = media.type.startsWith("video") ||
                            mimeType.startsWith("video/") ||
                            uriText.endsWith(".mp4") ||
                            uriText.endsWith(".m4v") ||
                            uriText.endsWith(".mov")
                        if (isVideo) {
                            val durationMs = viewModel.getProfileVideoDurationMs(uri)
                            if (durationMs > PROFILE_AVATAR_MAX_VIDEO_MS) {
                                trimCandidateUri = uri
                                trimCandidateDurationMs = durationMs
                            } else {
                                imageUri = uri
                                avatarMediaType = "video"
                                avatarDurationMs = durationMs.takeIf { it > 0L }?.coerceAtMost(PROFILE_AVATAR_MAX_VIDEO_MS)
                                avatarVideoMuted = true
                            }
                        } else {
                            if (mimeType == "image/gif" || uriText.endsWith(".gif")) {
                                imageUri = uri
                                avatarMediaType = "gif"
                                avatarDurationMs = null
                                avatarVideoMuted = true
                            } else {
                                pendingImageCrop = uri to ProfileImageTarget.AVATAR
                            }
                        }
                    } else {
                        pendingImageCrop = uri to ProfileImageTarget.COVER
                    }
                }
            }
        )
    }
    if (showProfileMusicPicker) {
        DeviceAudioPickerDialog(
            initialUri = profileMusicPickerInitialUri,
            title = "Música de entrada",
            confirmLabel = "Usar en mi perfil",
            maxClipDurationMs = 60_000L,
            defaultClipDurationMs = 30_000L,
            allowFullTrack = false,
            onBrowseFiles = { profileMusicFileLauncher.launch("audio/*") },
            onDismiss = {
                showProfileMusicPicker = false
                profileMusicPickerInitialUri = null
            },
            onClipReady = { clip ->
                pendingProfileMusic = clip
                profileMusicMarkedForRemoval = false
                showProfileMusicPicker = false
                profileMusicPickerInitialUri = null
            },
        )
    }
    }
    } // Account-scoped draft state.
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileAvatarTrimSheet(
    uri: Uri,
    durationMs: Long,
    isTrimming: Boolean,
    onDismiss: () -> Unit,
    onTrim: (Long) -> Unit
) {
    val maxStartMs = (durationMs - PROFILE_AVATAR_MAX_VIDEO_MS).coerceAtLeast(0L)
    var startMs by remember(uri, durationMs) { mutableStateOf(0f) }
    val safeStart = startMs.toLong().coerceIn(0L, maxStartMs)
    val safeEnd = (safeStart + PROFILE_AVATAR_MAX_VIDEO_MS).coerceAtMost(durationMs.coerceAtLeast(PROFILE_AVATAR_MAX_VIDEO_MS))

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Recortar avatar", style = MaterialTheme.typography.titleLarge)
            ProfileAvatarVideoPreview(
                uri = uri,
                startMs = safeStart,
                endMs = safeEnd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Text("Segmento: ${formatAvatarTrimTime(safeStart)} - ${formatAvatarTrimTime(safeEnd)}")
            Slider(
                value = safeStart.toFloat(),
                onValueChange = { startMs = it },
                valueRange = 0f..maxStartMs.toFloat().coerceAtLeast(1f),
                enabled = maxStartMs > 0L && !isTrimming
            )
            Button(
                onClick = { onTrim(safeStart) },
                enabled = !isTrimming,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTrimming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Usar estos 2 segundos")
                }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun ProfileAvatarVideoPreview(
    uri: Uri,
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            useSingleProfilePlayback()
            volume = 0f
        }
    }

    LaunchedEffect(startMs, endMs, exoPlayer) {
        exoPlayer.setMediaItem(profileAvatarPreviewItem(uri.toString(), startMs, endMs))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    conectate.com.presentation.social.PauseMomentPlayerOnBackground(exoPlayer)

    AndroidView(
        factory = { viewContext ->
            androidx.media3.ui.PlayerView(viewContext).apply {
                player = exoPlayer
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { it.player = exoPlayer },
        modifier = modifier
    )
}

private fun formatAvatarTrimTime(ms: Long): String {
    val totalTenths = (ms / 100L).coerceAtLeast(0L)
    val seconds = totalTenths / 10L
    val tenths = totalTenths % 10L
    return "$seconds.${tenths}s"
}

@Composable
internal fun ProfileSocialLinksEditor(
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
) {
    val fields = listOf(
        Triple(SocialPlatform.INSTAGRAM, instagram, onInstagramChange),
        Triple(SocialPlatform.TIKTOK, tiktok, onTikTokChange),
        Triple(SocialPlatform.FACEBOOK, facebook, onFacebookChange),
        Triple(SocialPlatform.X, xProfile, onXChange),
        Triple(SocialPlatform.WHATSAPP, whatsapp, onWhatsAppChange),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            fields.forEachIndexed { index, (platform, value, onValueChange) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SocialBrandLogo(platform = platform, size = 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = platform.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (platform == SocialPlatform.WHATSAPP) {
                                    KeyboardType.Phone
                                } else {
                                    KeyboardType.Uri
                                },
                                imeAction = if (index == fields.lastIndex) ImeAction.Done else ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (value.isBlank()) {
                                        Text(
                                            text = platform.placeholder,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                                            maxLines = 1,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }
                }
                if (index != fields.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 62.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

private fun normalizedSocialLinks(
    instagram: String,
    tiktok: String,
    facebook: String,
    xProfile: String,
    whatsapp: String,
): Map<String, String> = ProfileSocialLinks.normalize(
    mapOf(
        "instagram" to instagram,
        "tiktok" to tiktok,
        "facebook" to facebook,
        "x" to xProfile,
        "whatsapp" to whatsapp,
    ),
)

private fun parseProfileList(value: String): List<String> = value
    .split(',', '\n', ';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy(String::lowercase)
    .take(24)

private fun socialLinksAreValid(
    instagram: String,
    tiktok: String,
    facebook: String,
    xProfile: String,
    whatsapp: String,
): Boolean {
    return ProfileSocialLinks.isValidInput("instagram", instagram) &&
        ProfileSocialLinks.isValidInput("tiktok", tiktok) &&
        ProfileSocialLinks.isValidInput("facebook", facebook) &&
        ProfileSocialLinks.isValidInput("x", xProfile) &&
        ProfileSocialLinks.isValidInput("whatsapp", whatsapp)
}

enum class ProfileImageTarget {
    AVATAR,
    COVER
}


// Store metadata only; files remain in the existing private draft/cache storage.
private val ProfileMusicDraftSaver = listSaver<PreparedAudioClip?, Any>(
    save = { clip -> if (clip == null) emptyList() else listOf(
        clip.uri.toString(), clip.displayName, clip.mimeType, clip.title, clip.artist, clip.album,
        clip.durationMs, clip.artworkUri?.toString().orEmpty(), clip.sourceUri?.toString().orEmpty(), clip.sourceStartMs,
    ) },
    restore = { values -> if (values.isEmpty()) null else PreparedAudioClip(
        uri = Uri.parse(values[0] as String), displayName = values[1] as String, mimeType = values[2] as String,
        title = values[3] as String, artist = values[4] as String, album = values[5] as String,
        durationMs = values[6] as Long, artworkUri = (values[7] as String).takeIf(String::isNotBlank)?.let(Uri::parse),
        sourceUri = (values[8] as String).takeIf(String::isNotBlank)?.let(Uri::parse), sourceStartMs = values[9] as Long,
    ) },
)
