package conectate.com.presentation.messages

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Bedtime
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    groupId: String,
    inviteToken: String? = null,
    navController: NavController,
    viewModel: GroupDetailViewModel = hiltViewModel()
) {
    val group by viewModel.group.collectAsState()
    val members by viewModel.members.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isDeleted by viewModel.isDeleted.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeletedByOtherAlert by remember { mutableStateOf(false) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var shareLink by remember { mutableStateOf<String?>(null) }
    var showRestDialog by remember { mutableStateOf(false) }
    
    // Alerta inmediata de borrado
    LaunchedEffect(isDeleted) {
        if (isDeleted) {
            showDeletedByOtherAlert = true
        }
    }
    
    // Cargar detalles al iniciar la pantalla
    LaunchedEffect(groupId, inviteToken) {
        viewModel.loadGroupDetails(groupId, inviteToken)
    }

    LaunchedEffect(actionMessage) {
        if (!actionMessage.isNullOrBlank()) {
            android.widget.Toast.makeText(context, actionMessage, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeActionMessage()
        }
    }

    // Detectar si el grupo ha sido eliminado o el usuario expulsado
    LaunchedEffect(group, members, isLoading) {
        if (!isLoading && group != null && currentUserId != null) {
            // Si ya cargó el grupo y los miembros, verificamos si el usuario sigue siendo parte
            // Nota: El creador siempre es miembro, así que esto es seguro.
            val isStillMember = members.any { it.user_id == currentUserId }
            if (group?.is_deleted == true || (members.isNotEmpty() && !isStillMember)) {
                showDeletedByOtherAlert = true
            }
        } else if (!isLoading && group == null) {
            // Si no carga el grupo en absoluto (borrado total o id inválida)
            showDeletedByOtherAlert = true
        }
    }

    var showEditGroupDialog by remember { mutableStateOf(false) }

    // Check permissions
    val normalizedCurrentUserId = currentUserId?.trim()?.lowercase().orEmpty()
    val normalizedCreatorId = group?.creator_id?.trim()?.lowercase().orEmpty()
    val amICreator = normalizedCurrentUserId.isNotBlank() && normalizedCurrentUserId == normalizedCreatorId
    val amIAdmin = members.any {
        it.user_id.trim().lowercase() == normalizedCurrentUserId &&
            normalizedGroupRole(it.role) in setOf("admin", "owner", "creator")
    }
    // La UI no debe heredar permisos desde estados viejos.
    // Solo el creador real o un miembro con rol admin/owner/creator puede gestionar.
    val canManageMembers = amICreator || amIAdmin

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: "Detalles del grupo") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    if (canManageMembers) {
                        IconButton(onClick = { showAddMemberDialog = true }) {
                            Icon(Icons.Default.PersonAdd, "Agregar miembro", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Opciones", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (canManageMembers) {
                            DropdownMenuItem(
                                text = { Text("Editar información") },
                                onClick = {
                                    showMenu = false
                                    showEditGroupDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Compartir enlace o QR") },
                                onClick = {
                                    showMenu = false
                                    viewModel.createShareLink { shareLink = it }
                                },
                                leadingIcon = { Icon(Icons.Default.Share, null) }
                            )
                            HorizontalDivider()

                            if (amICreator) {
                                DropdownMenuItem(
                                    text = { Text("Eliminar grupo para todos") },
                                    onClick = {
                                        showMenu = false
                                        showDeleteConfirm = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    }
                                )
                            }
                        }
                        if (group != null) {
                            DropdownMenuItem(
                                text = { Text("Modo descanso") },
                                onClick = {
                                    showMenu = false
                                    showRestDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Bedtime, null) }
                            )
                        }
                        if (group != null && !amICreator) {
                            if (canManageMembers) HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Salir del grupo") },
                                onClick = {
                                    showMenu = false
                                    viewModel.leaveGroup {
                                        navController.navigate(conectate.com.presentation.navigation.Screen.Messages.route) {
                                            popUpTo(conectate.com.presentation.navigation.Screen.Messages.route) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            )
                        } else if (group == null) {
                            DropdownMenuItem(
                                text = { Text("Borrar de mi lista") },
                                onClick = {
                                    showMenu = false
                                    viewModel.deleteGroupLocally {
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
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Group Info Card
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
                            if (!group?.image.isNullOrEmpty()) {
                                AsyncImage(
                                    model = group?.image,
                                    contentDescription = "Imagen del grupo",
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .clickable {
                                            navController.navigate(Screen.FullScreenImage.createRoute(group!!.image!!))
                                        },
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Group,
                                    contentDescription = "Grupo",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(80.dp)
                                )
                            }

                            Text(
                                text = group?.name ?: "",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            
                            if (group?.description != null) {
                                Text(
                                    text = group!!.description!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

                // Leave Button (only if not creator)
                item {
                    if (group != null && !amICreator) {
                        OutlinedButton(
                            onClick = { 
                                viewModel.leaveGroup {
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
                            Text("Salir del grupo")
                        }
                    }
                }

                item {
                    Text(
                        text = "Miembros",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                items(members) { member ->
                    val memberRole = groupMemberDisplayRole(
                        memberUserId = member.user_id,
                        creatorId = group?.creator_id,
                        rawRole = member.role
                    )
                    GroupMemberItem(
                        userId = member.user_id,
                        userName = member.userName,
                        userImage = member.userImage,
                        role = memberRole,
                        isCurrentUser = member.user_id.trim().lowercase() == normalizedCurrentUserId,
                        canManageMember = canManageMembers &&
                            member.user_id.trim().lowercase() != normalizedCurrentUserId &&
                            member.user_id.trim().lowercase() != normalizedCreatorId,
                        onRemove = { viewModel.removeMember(member.user_id) },
                        onPromote = { viewModel.promoteToAdmin(member.user_id) },
                        onDemote = { viewModel.demoteToMember(member.user_id) },
                        navController = navController
                    )
                }
            }
        }
    }
    
    if (showAddMemberDialog) {
        AddMemberDialog(
            onDismiss = { showAddMemberDialog = false },
            onSearch = { viewModel.searchUsers(it) },
            searchResults = searchResults,
            onAddUser = { userId ->
                viewModel.addMember(userId) {
                    showAddMemberDialog = false
                }
            }
        )
    }

    shareLink?.let { link ->
        CollectionShareDialog(
            title = "Compartir grupo",
            link = link,
            onDismiss = { shareLink = null }
        )
    }

    if (showRestDialog) {
        AlertDialog(
            onDismissRequest = { showRestDialog = false },
            title = { Text("Modo descanso") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Silencia las notificaciones del grupo sin salir ni perder mensajes.")
                    TextButton(onClick = { showRestDialog = false; viewModel.setRestDuration(8) }) { Text("8 horas") }
                    TextButton(onClick = { showRestDialog = false; viewModel.setRestDuration(24) }) { Text("24 horas") }
                    TextButton(onClick = { showRestDialog = false; viewModel.setRestDuration(168) }) { Text("7 días") }
                    TextButton(onClick = { showRestDialog = false; viewModel.setRestDuration(null) }) { Text("Desactivar") }
                }
            },
            confirmButton = {}
        )
    }

    if (showEditGroupDialog) {
        EditGroupDialog(
            group = group!!,
            onDismiss = { showEditGroupDialog = false },
            onConfirm = { name, desc, img, onlyAdmins ->
                viewModel.updateGroupInfo(name, desc, img, onlyAdmins) {
                    showEditGroupDialog = false
                }
            }
        )
    }

    // Diálogo de confirmación para el creador
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("¿Eliminar grupo?") },
            text = { Text("Esta acción eliminará el grupo para todos los miembros y no se puede deshacer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteGroup {
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

    // Alerta global cuando el grupo es eliminado por otro
    if (showDeletedByOtherAlert) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Grupo no disponible") },
            text = { Text("Ya no formas parte de este grupo o ha sido eliminado.") },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGroupDialog(
    group: conectate.com.data.model.Group,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, ByteArray?, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(group.name) }
    var description by remember { mutableStateOf(group.description ?: "") }
    var onlyAdminsCanTalk by remember { mutableStateOf(group.only_admins_can_talk) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var imageToCrop by remember { mutableStateOf<android.net.Uri?>(null) }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        imageToCrop = uri
    }

    imageToCrop?.let { sourceUri ->
        conectate.com.presentation.components.SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Recortar foto del grupo",
            presets = listOf(conectate.com.presentation.components.ImageCropPreset.SQUARE),
            initialPreset = conectate.com.presentation.components.ImageCropPreset.SQUARE,
            onDismiss = { imageToCrop = null },
            onCropped = { croppedUri ->
                selectedImageBytes = runCatching {
                    context.contentResolver.openInputStream(croppedUri)?.use { it.readBytes() }
                }.getOrNull()
                imageToCrop = null
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar grupo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Solo admins pueden hablar")
                    Switch(checked = onlyAdminsCanTalk, onCheckedChange = { onlyAdminsCanTalk = it })
                }

                Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cambiar Foto")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, description, selectedImageBytes, onlyAdminsCanTalk) }) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMemberDialog(
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    searchResults: List<conectate.com.data.model.User>,
    onAddUser: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    
    // Auto-search when query changes
    LaunchedEffect(query) {
        if (query.length >= 3) {
            onSearch(query)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar miembro") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar usuario") },
                    placeholder = { Text("Nombre o usuario...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                
                LazyColumn(
                    modifier = Modifier.height(200.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (searchResults.isEmpty() && query.length >= 3) {
                        item { Text("No se encontraron usuarios", style = MaterialTheme.typography.bodySmall) }
                    }
                    
                    items(searchResults) { user ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddUser(user.id) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProfileImage(
                                imageUrl = user.avatar_url,
                                size = 40.dp,
                                fallbackName = user.displayName
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(user.displayName, style = MaterialTheme.typography.bodyMedium)
                                user.username?.let {
                                     Text("@$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
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
fun GroupMemberItem(
    userId: String,
    userName: String?,
    userImage: String?,
    role: String,
    isCurrentUser: Boolean,
    canManageMember: Boolean,
    onRemove: () -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
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
        CircularProfileImage(
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
                text = groupRoleLabel(role),
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
                    if (role == "member") {
                        DropdownMenuItem(text = { Text("Subir a Admin") }, onClick = { showOptions = false; onPromote() })
                    } else if (role == "admin") {
                        DropdownMenuItem(text = { Text("Quitar Admin") }, onClick = { showOptions = false; onDemote() })
                    }
                    DropdownMenuItem(
                        text = { Text("Eliminar del grupo") },
                        onClick = { showOptions = false; onRemove() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    }

}

private fun normalizedGroupRole(role: String?): String =
    role?.trim()?.lowercase().orEmpty()

private fun groupMemberDisplayRole(
    memberUserId: String,
    creatorId: String?,
    rawRole: String?
): String {
    val cleanMemberId = memberUserId.trim().lowercase()
    val cleanCreatorId = creatorId?.trim()?.lowercase().orEmpty()
    if (cleanMemberId.isNotBlank() && cleanMemberId == cleanCreatorId) return "owner"
    val role = normalizedGroupRole(rawRole)
    return when (role) {
        "creator", "owner" -> "owner"
        "admin" -> "admin"
        else -> "member"
    }
}

private fun groupRoleLabel(role: String): String =
    when (normalizedGroupRole(role)) {
        "owner", "creator" -> "Admin principal"
        "admin" -> "Admin"
        else -> "Miembro"
    }
