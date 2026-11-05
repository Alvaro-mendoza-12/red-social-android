package conectate.com.presentation.messages

import conectate.com.core.media.readMediaBytesWithLimit

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.data.model.CommunityRoleCatalog
import conectate.com.data.model.CommunityRoleCapabilities
import conectate.com.data.model.CommunityEvent
import conectate.com.data.model.CommunityMemberProfile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityDetailScreen(
    communityId: String,
    navController: NavController,
    viewModel: CommunityDetailViewModel = hiltViewModel()
) {
    val community by viewModel.community.collectAsState()
    val members by viewModel.members.collectAsState()
    val isAdmin by viewModel.isAdmin.collectAsState()
    val isMember by viewModel.isMember.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val roleCatalog by viewModel.roleCatalog.collectAsState()
    val communityProfile by viewModel.communityProfile.collectAsState()
    val communityProfiles by viewModel.communityProfiles.collectAsState()
    val events by viewModel.events.collectAsState()
    val canManageEvents by viewModel.canManageEvents.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val membershipPolicy by viewModel.membershipPolicy.collectAsState()
    val membershipBusy by viewModel.membershipBusy.collectAsState()
    val membershipError by viewModel.membershipError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var shareLink by remember(communityId) { mutableStateOf<String?>(null) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    val searchResults by viewModel.searchResults.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeletedByOtherAlert by remember { mutableStateOf(false) }
    var showEditCommunityDialog by remember { mutableStateOf(false) }
    var showRolePreviewDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showCreateEventDialog by remember { mutableStateOf(false) }
    var showFirstContribution by remember(communityId,currentUserId) { mutableStateOf(false) }
    var purposeEventId by remember(communityId,currentUserId) { mutableStateOf<String?>(null) }
    if(showFirstContribution) conectate.com.presentation.purpose.PurposeExperienceDialog(
        conectate.com.presentation.purpose.PurposeTarget("community",communityId),onDismiss = { showFirstContribution = false },onSource = { source ->
            when(source.kind) {
                "question" -> navController.navigate(Screen.CommunityChat.createRoute(communityId,source.id))
                "guide" -> navController.navigate(Screen.UserProfile.createRoute(source.id))
                "event" -> { purposeEventId = source.id; viewModel.loadCommunityDetails(communityId) }
                else -> conectate.com.presentation.common.AppFeedbackBus.show("Esa actividad ya no está disponible.")
            }
        })
    purposeEventId?.let { id ->
        val event = events.find { it.id == id }
        AlertDialog(onDismissRequest = { purposeEventId = null },title = { Text("Encuentro de la comunidad") },text = {
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(event != null) CommunityEventCard(event,
                    onAttendanceChange = { attending,reminder -> viewModel.setEventAttendance(event,attending,reminder) },
                    onCancel = { viewModel.cancelCommunityEvent(event) })
                else if(!isLoading) Text("El encuentro ya no está disponible. Actualiza para comprobarlo.")
                actionMessage?.let { Text(it) }
            }
        },confirmButton = { TextButton(onClick = { purposeEventId = null }) { Text("Cerrar") } },dismissButton = {
            TextButton(enabled = !isLoading,onClick = { viewModel.loadCommunityDetails(communityId) }) { Text("Actualizar") }
        })
    }
    
    val normalizedCurrentUserId = currentUserId?.trim()?.lowercase().orEmpty()
    val normalizedCreatorId = community?.creator_id?.trim()?.lowercase().orEmpty()
    val amICreator = normalizedCurrentUserId.isNotBlank() && normalizedCurrentUserId == normalizedCreatorId
    val amIAdmin = members.any {
        it.user_id.trim().lowercase() == normalizedCurrentUserId &&
            normalizedCommunityRole(it.role) in setOf("admin", "owner", "creator")
    }
    val canManageMembers = isAdmin || amICreator || amIAdmin

    // Cargar detalles al iniciar la pantalla
    LaunchedEffect(communityId) {
        viewModel.loadCommunityDetails(communityId)
    }

    LaunchedEffect(actionMessage) {
        val message = actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeActionMessage()
    }

    // Detectar si la comunidad ha sido eliminada o el usuario expulsado
    LaunchedEffect(community, members, isLoading) {
        if (!isLoading && community != null && currentUserId != null) {
            val isStillMember = members.any { it.user_id == currentUserId }
            if (community?.is_deleted == true || (community?.is_public == false && members.isNotEmpty() && !isStillMember)) {
                showDeletedByOtherAlert = true
            }
        } else if (!isLoading && community == null && loadError == null) {
            showDeletedByOtherAlert = true
        }
    }

    shareLink?.let { link -> CollectionShareDialog(
        title = "Compartir comunidad", link = link, navController = navController,
        canRevoke = canManageMembers, onDismiss = { shareLink = null }
    ) }

    Scaffold(
        snackbarHost = {
            conectate.com.presentation.common.KeyboardSafeSnackbarHost(snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = { Text(community?.name ?: "Detalles de la comunidad", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    if (isMember) IconButton(onClick = { viewModel.createShareLink { shareLink = it } }) {
                        Icon(Icons.Default.Share, "Compartir enlace o QR")
                    }
                    if (membershipPolicy?.canAddMembers == true) IconButton(onClick = {
                        viewModel.refreshMembershipPolicy(); showAddMemberDialog = true
                    }) { Icon(Icons.Default.PersonAdd, "Añadir persona") }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Opciones", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if(isMember) DropdownMenuItem(text = { Text("Tu primera contribución") },onClick = { showMenu = false; showFirstContribution = true },leadingIcon = { Icon(Icons.Default.PersonAdd,null) })
                        if (isMember) DropdownMenuItem(
                            text = { Text("Compartir enlace o QR") },
                            onClick = { showMenu = false; viewModel.createShareLink { shareLink = it } },
                            leadingIcon = { Icon(Icons.Default.Share, null) }
                        )
                        if (canManageMembers) {
                            DropdownMenuItem(
                                text = { Text("Vista como rol") },
                                onClick = {
                                    showMenu = false
                                    showRolePreviewDialog = true
                                    viewModel.loadRoleCatalog()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Visibility, contentDescription = null)
                                }
                            )
                            HorizontalDivider()
                        }
                        if (isMember) {
                            DropdownMenuItem(
                                text = { Text("Mi perfil en la comunidad") },
                                onClick = {
                                    showMenu = false
                                    showProfileDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Person, contentDescription = null)
                                }
                            )
                            HorizontalDivider()
                        }
                        if (amICreator) {
                            DropdownMenuItem(
                                text = { Text("Editar información") },
                                onClick = {
                                    showMenu = false
                                    showEditCommunityDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, null)
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Eliminar comunidad para todos") },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirm = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Borrar de mi lista") },
                                onClick = {
                                    showMenu = false
                                    viewModel.deleteCommunityLocally {
                                        navController.navigate(conectate.com.presentation.navigation.Screen.Messages.route) {
                                            popUpTo(conectate.com.presentation.navigation.Screen.Messages.route) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (community == null && loadError != null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(loadError.orEmpty())
                Button(onClick = viewModel::retryCommunityDetails) { Text("Reintentar") }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Community Info Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (!community?.image.isNullOrEmpty()) {
                                AsyncImage(
                                    model = conectate.com.core.media.MediaImageRequestFactory.avatar(androidx.compose.ui.platform.LocalContext.current, conectate.com.data.localfallback.UbuntuStorageUrl.collectionAvatar("community", community?.image)),
                                    contentDescription = "Imagen de la comunidad",
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .clickable {
                                            conectate.com.data.localfallback.UbuntuStorageUrl.collectionAvatar("community", community?.image)?.let { navController.navigate(Screen.FullScreenImage.createRoute(it)) }
                                        },
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = if (community?.is_public == true) Icons.Default.Public else Icons.Default.Lock,
                                    contentDescription = if (community?.is_public == true) "Pública" else "Privada",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(80.dp)
                                )
                            }

                            Text(
                                text = community?.name ?: "",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            
                            if (community?.description != null) {
                                Text(
                                    text = community!!.description!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            if (community?.category != null) {
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ) {
                                    Text(
                                        text = community!!.category!!,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    )
                                }
                            }
                            
                            if (community?.only_admins_can_talk == true) {
                                Text(
                                    text = "🔒 Solo administradores pueden escribir",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            Text(
                                text = "${members.size} miembros",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Join/Leave Button
                item {
                    if (!isMember && community?.is_public == true) {
                        Button(
                            onClick = { viewModel.joinCommunity() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Unirse a la comunidad")
                        }
                    } else if (isMember && community?.creator_id != currentUserId) {
                        OutlinedButton(
                            onClick = { 
                                viewModel.leaveCommunity {
                                    navController.popBackStack()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Salir de la comunidad")
                        }
                    }
                }

                if (isMember) {
                    item {
                        CommunityIdentityCard(
                            profile = communityProfile,
                            onEdit = { showProfileDialog = true }
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Eventos",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Encuentros y actividades de la comunidad",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (canManageEvents || isAdmin) {
                                FilledTonalIconButton(
                                    onClick = { showCreateEventDialog = true }
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Crear evento")
                                }
                            }
                        }
                    }

                    if (events.isEmpty()) {
                        item {
                            Text(
                                text = "Todavía no hay próximos eventos.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        items(events, key = { "event-${it.id}" }) { event ->
                            CommunityEventCard(
                                event = event,
                                onAttendanceChange = { attending, reminderMinutes ->
                                    viewModel.setEventAttendance(
                                        event = event,
                                        attending = attending,
                                        reminderMinutes = reminderMinutes
                                    )
                                },
                                onCancel = { viewModel.cancelCommunityEvent(event) }
                            )
                        }
                    }
                }

                item { CollectionMembershipSettings(membershipPolicy, membershipBusy, viewModel::setMembersCanAdd) }

                // Members Section
                if (isMember || community?.is_public == true) {
                    item {
                        Text(
                            text = "Miembros",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    items(members) { member ->
                        val memberCommunityProfile = communityProfiles[
                            member.user_id.trim().lowercase()
                        ]
                        val memberRole = communityMemberDisplayRole(
                            memberUserId = member.user_id,
                            creatorId = community?.creator_id,
                            rawRole = member.role
                        )
                        CommunityMemberItem(
                            userId = member.user_id,
                            userName = memberCommunityProfile?.alias
                                ?.let { alias ->
                                    memberCommunityProfile.distinctive
                                        ?.takeIf(String::isNotBlank)
                                        ?.let { "$it $alias" }
                                        ?: alias
                                }
                                ?: member.userName,
                            userImage = member.userImage,
                            role = memberRole,
                            isCurrentUser = member.user_id.trim().lowercase() == normalizedCurrentUserId,
                            canAssignRole = amICreator,
                            canManageMember = canManageMembers &&
                                member.user_id.trim().lowercase() != normalizedCurrentUserId &&
                                member.user_id.trim().lowercase() != normalizedCreatorId,
                            onRemove = { viewModel.removeMember(member.user_id) },
                            onRoleChange = { role ->
                                viewModel.setMemberRole(member.user_id, role)
                            },
                            navController = navController
                        )
                    }
                }
            }
        }
    }

    if (showRolePreviewDialog) {
        CommunityRolePreviewDialog(
            catalog = roleCatalog,
            onDismiss = { showRolePreviewDialog = false }
        )
    }

    if (showProfileDialog) {
        CommunityProfileDialog(
            profile = communityProfile,
            onDismiss = { showProfileDialog = false },
            onSave = { alias, bio, distinctive ->
                viewModel.saveCommunityProfile(alias, bio, distinctive) {
                    showProfileDialog = false
                }
            }
        )
    }

    if (showCreateEventDialog) {
        CreateCommunityEventDialog(
            onDismiss = { showCreateEventDialog = false },
            onCreate = { title, description, startsAt, location ->
                viewModel.createCommunityEvent(
                    title = title,
                    description = description,
                    startsAt = startsAt,
                    location = location
                ) {
                    showCreateEventDialog = false
                }
            }
        )
    }

    if (showAddMemberDialog) CollectionAddMemberDialog(
        onDismiss = { showAddMemberDialog = false }, onSearch = viewModel::searchUsers,
        searchResults = searchResults, policy = membershipPolicy, busy = membershipBusy, error = membershipError,
        onAdd = { userId, from, until -> viewModel.addMember(userId, from, until) { showAddMemberDialog = false } }
    )

    if (showEditCommunityDialog) {
        EditCommunityDialog(
            community = community!!,
            onDismiss = { showEditCommunityDialog = false },
            onConfirm = { name, desc, img, isPub, onlyAdmins, limit ->
                viewModel.updateCommunityInfo(name, desc, img, isPub, onlyAdmins, limit) {
                    showEditCommunityDialog = false
                }
            }
        )
    }

    // Diálogo de confirmación para el creador
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("¿Eliminar comunidad?") },
            text = { Text("Esta acción eliminará la comunidad para todos los miembros y no se puede deshacer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteCommunity {
                            navController.popBackStack()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Alerta global cuando la comunidad es eliminada por otro
    if (showDeletedByOtherAlert) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Comunidad no disponible") },
            text = { Text("Ya no formas parte de esta comunidad o ha sido eliminada.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeletedByOtherAlert = false
                        navController.popBackStack()
                    }
                ) {
                    Text("Aceptar")
                }
            }
        )
    }
}

@Composable
private fun CommunityIdentityCard(
    profile: CommunityMemberProfile?,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Mi perfil en esta comunidad",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            val displayAlias = profile?.alias?.takeIf(String::isNotBlank)
            val displayDistinctive = profile?.distinctive?.takeIf(String::isNotBlank)
            Text(
                text = listOfNotNull(displayDistinctive, displayAlias)
                    .joinToString(" ")
                    .ifBlank { "Usas tu nombre general" },
                style = MaterialTheme.typography.bodyLarge
            )
            profile?.bio?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Editar perfil de la comunidad")
            }
        }
    }
}

@Composable
private fun CommunityProfileDialog(
    profile: CommunityMemberProfile?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var alias by remember(profile) { mutableStateOf(profile?.alias.orEmpty()) }
    var bio by remember(profile) { mutableStateOf(profile?.bio.orEmpty()) }
    var distinctive by remember(profile) { mutableStateOf(profile?.distinctive.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mi perfil en la comunidad") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Este alias, biografía y distintivo solo se mostrarán dentro de esta comunidad.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it.take(50) },
                    label = { Text("Alias") },
                    supportingText = { Text("${alias.length}/50") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it.take(240) },
                    label = { Text("Biografía") },
                    supportingText = { Text("${bio.length}/240") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = distinctive,
                    onValueChange = { distinctive = it.take(24) },
                    label = { Text("Distintivo") },
                    placeholder = { Text("Ejemplo: 🔭") },
                    supportingText = { Text("Emoji o texto corto · ${distinctive.length}/24") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(alias, bio, distinctive) }) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
internal fun CommunityEventCard(
    event: CommunityEvent,
    onAttendanceChange: (Boolean, Int?) -> Unit,
    onCancel: () -> Unit
) {
    var reminderMenuExpanded by remember { mutableStateOf(false) }
    val attending = event.currentAttendance == "going"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Event,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = formatCommunityEventDate(event.startsAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            event.location?.takeIf(String::isNotBlank)?.let {
                Text("Lugar: $it", style = MaterialTheme.typography.bodyMedium)
            }
            event.description?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "${event.attendanceCount} asistirán",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (event.isCancelled) {
                Text(
                    text = "Evento cancelado",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Button(
                    onClick = {
                        onAttendanceChange(!attending, if (attending) null else event.reminderMinutes)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (attending) "Cancelar mi asistencia" else "Confirmar asistencia")
                }
                if (attending) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { reminderMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(communityReminderLabel(event.reminderMinutes))
                        }
                        DropdownMenu(
                            expanded = reminderMenuExpanded,
                            onDismissRequest = { reminderMenuExpanded = false }
                        ) {
                            listOf<Int?>(null, 15, 30, 60, 1440).forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text(communityReminderLabel(minutes)) },
                                    onClick = {
                                        reminderMenuExpanded = false
                                        onAttendanceChange(true, minutes)
                                    }
                                )
                            }
                        }
                    }
                }
                if (event.canManage) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Cancelar evento para todos")
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateCommunityEventDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var startsAtMillis by remember { mutableLongStateOf(System.currentTimeMillis() + 86_400_000L) }

    fun openDateTimePicker() {
        val calendar = Calendar.getInstance().apply { timeInMillis = startsAtMillis }
        android.app.DatePickerDialog(
            context,
            { _, year, month, day ->
                android.app.TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        startsAtMillis = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = System.currentTimeMillis() - 1_000L
        }.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crear evento") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    label = { Text("Título") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(500) },
                    label = { Text("Descripción") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it.take(160) },
                    label = { Text("Lugar o enlace") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = ::openDateTimePicker,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Event, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(formatCommunityEventMillis(startsAtMillis))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        title,
                        description,
                        Instant.ofEpochMilli(startsAtMillis).toString(),
                        location
                    )
                },
                enabled = title.isNotBlank() && startsAtMillis > System.currentTimeMillis()
            ) {
                Text("Crear")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

private val communityEventFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm")

private fun formatCommunityEventDate(value: String): String =
    runCatching {
        Instant.parse(value)
            .atZone(ZoneId.systemDefault())
            .format(communityEventFormatter)
    }.getOrDefault("Fecha por confirmar")

private fun formatCommunityEventMillis(value: Long): String =
    Instant.ofEpochMilli(value)
        .atZone(ZoneId.systemDefault())
        .format(communityEventFormatter)

private fun communityReminderLabel(minutes: Int?): String =
    when (minutes) {
        15 -> "Recordar 15 minutos antes"
        30 -> "Recordar 30 minutos antes"
        60 -> "Recordar 1 hora antes"
        1440 -> "Recordar 1 día antes"
        0 -> "Recordar al comenzar"
        else -> "Sin recordatorio"
    }

@Composable
private fun CommunityRolePreviewDialog(
    catalog: CommunityRoleCatalog?,
    onDismiss: () -> Unit
) {
    var selectedRole by remember(catalog?.currentRole) {
        mutableStateOf(catalog?.currentRole ?: "member")
    }
    val selected = catalog?.roles?.firstOrNull { it.role == selectedRole }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vista como rol") },
        text = {
            if (catalog == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Tu rol actual: ${
                            catalog.roles.firstOrNull { it.role == catalog.currentRole }?.label
                                ?: communityRoleLabel(catalog.currentRole)
                        }",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Esta vista solo previsualiza permisos; no cambia ningún rol.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    catalog.roles.forEach { role ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRole = role.role }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedRole == role.role,
                                onClick = { selectedRole = role.role }
                            )
                            Text(role.label)
                        }
                    }
                    HorizontalDivider()
                    selected?.let { CommunityRolePermissionList(it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
private fun CommunityRolePermissionList(role: CommunityRoleCapabilities) {
    val permissions = listOf(
        "Publicar y participar" to role.canPost,
        "Moderar mensajes" to role.canModerateMessages,
        "Gestionar miembros" to role.canManageMembers,
        "Asignar roles" to role.canManageRoles,
        "Gestionar canales" to role.canManageChannels,
        "Gestionar eventos" to role.canManageEvents,
        "Editar la comunidad" to role.canEditCommunity,
        "Pausar canales" to role.canPauseChannels
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Permisos de ${role.label}",
            style = MaterialTheme.typography.titleSmall
        )
        permissions.forEach { (label, allowed) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, modifier = Modifier.weight(1f))
                Text(
                    text = if (allowed) "Permitido" else "No permitido",
                    color = if (allowed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCommunityDialog(
    community: conectate.com.data.model.Community,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, ByteArray?, Boolean, Boolean, Int) -> Unit
) {
    var name by remember { mutableStateOf(community.name) }
    var description by remember { mutableStateOf(community.description ?: "") }
    var isPublic by remember { mutableStateOf(community.is_public) }
    var onlyAdminsCanTalk by remember { mutableStateOf(community.only_admins_can_talk) }
    var weeklyMessageLimit by remember { mutableStateOf(community.weekly_message_limit.toString()) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var imageToCrop by remember { mutableStateOf<android.net.Uri?>(null) }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = conectate.com.presentation.components.rememberInAppMediaPicker() { uri ->
        imageToCrop = uri
    }

    imageToCrop?.let { sourceUri ->
        conectate.com.presentation.components.SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Recortar foto de la comunidad",
            presets = listOf(conectate.com.presentation.components.ImageCropPreset.SQUARE),
            initialPreset = conectate.com.presentation.components.ImageCropPreset.SQUARE,
            onDismiss = { imageToCrop = null },
            onCropped = { croppedUri ->
                selectedImageBytes = runCatching {
                    context.contentResolver.openInputStream(croppedUri)?.use { it.readMediaBytesWithLimit(60L * 1024 * 1024) }
                }.getOrNull()
                imageToCrop = null
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar comunidad") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") })
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descripción") })
                OutlinedTextField(
                    value = weeklyMessageLimit,
                    onValueChange = { weeklyMessageLimit = it.filter { char -> char.isDigit() }.take(5) },
                    label = { Text("Mensajes por integrante a la semana") },
                    enabled = !onlyAdminsCanTalk,
                    supportingText = { Text("0: sin límite. Se renueva los lunes a las 00:00 UTC. Administradores exentos.") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Pública")
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Solo admins pueden hablar")
                    Switch(checked = onlyAdminsCanTalk, onCheckedChange = {
                        onlyAdminsCanTalk = it
                        if (!it && weeklyMessageLimit.toIntOrNull() == 0) weeklyMessageLimit = "10"
                    })
                }
                Button(onClick = { launcher.launch("image/*") }) {
                    Text("Cambiar Foto")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val limit = (weeklyMessageLimit.toIntOrNull() ?: 0).coerceIn(0, 10000)
                onConfirm(name, description, selectedImageBytes, isPublic, onlyAdminsCanTalk, limit)
            }) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun CommunityMemberItem(
    userId: String,
    userName: String?,
    userImage: String?,
    role: String,
    isCurrentUser: Boolean,
    canManageMember: Boolean,
    canAssignRole: Boolean = canManageMember,
    onRoleChange: (String) -> Unit,
    onRemove: () -> Unit,
    navController: NavController
) {
    var showOptions by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                navController.navigate(Screen.UserProfile.createRoute(userId))
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProfileImage(ownerId = userId, 
            imageUrl = userImage,
            size = 48.dp,
            fallbackName = if (isCurrentUser) "Tú" else userName,
            modifier = Modifier.clickable {
                if (!userImage.isNullOrEmpty()) {
                    navController.navigate(Screen.FullScreenImage.createRoute(userImage))
                }
            }
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isCurrentUser) "Tú" else (userName ?: "Usuario"),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = communityRoleLabel(role),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (canManageMember) {
            Box {
                IconButton(onClick = { showOptions = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Opciones")
                }
                DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                    val normalizedRole = normalizedCommunityRole(role)
                    if (canAssignRole) {
                    if (normalizedRole != "admin") {
                        DropdownMenuItem(
                            text = { Text("Asignar Administrador") },
                            onClick = { showOptions = false; onRoleChange("admin") }
                        )
                    }
                    if (normalizedRole != "moderator") {
                        DropdownMenuItem(
                            text = { Text("Asignar Moderador") },
                            onClick = { showOptions = false; onRoleChange("moderator") }
                        )
                    }
                    if (normalizedRole != "member") {
                        DropdownMenuItem(
                            text = { Text("Asignar Miembro") },
                            onClick = { showOptions = false; onRoleChange("member") }
                        )
                    }
                    }
                    DropdownMenuItem(
                        text = { Text("Expulsar miembro") },
                        onClick = { showOptions = false; onRemove() },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    )
                }
            }
        }
    }
}

private fun normalizedCommunityRole(role: String?): String =
    role?.trim()?.lowercase().orEmpty()

private fun communityMemberDisplayRole(
    memberUserId: String,
    creatorId: String?,
    rawRole: String?
): String {
    val cleanMemberId = memberUserId.trim().lowercase()
    val cleanCreatorId = creatorId?.trim()?.lowercase().orEmpty()
    if (cleanMemberId.isNotBlank() && cleanMemberId == cleanCreatorId) return "owner"
    val role = normalizedCommunityRole(rawRole)
    return when (role) {
        "creator", "owner" -> "owner"
        "admin" -> "admin"
        "moderator", "mod" -> "moderator"
        else -> "member"
    }
}

private fun communityRoleLabel(role: String): String =
    when (normalizedCommunityRole(role)) {
        "owner", "creator" -> "Propietario"
        "admin" -> "Administrador"
        "moderator", "mod" -> "Moderador"
        else -> "Miembro"
    }
