package conectate.com.presentation.messages

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
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
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeletedByOtherAlert by remember { mutableStateOf(false) }
    var showEditCommunityDialog by remember { mutableStateOf(false) }
    
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

    // Detectar si la comunidad ha sido eliminada o el usuario expulsado
    LaunchedEffect(community, members, isLoading) {
        if (!isLoading && community != null && currentUserId != null) {
            val isStillMember = members.any { it.user_id == currentUserId }
            if (community?.is_deleted == true || (community?.is_public == false && members.isNotEmpty() && !isStillMember)) {
                showDeletedByOtherAlert = true
            }
        } else if (!isLoading && community == null) {
            showDeletedByOtherAlert = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(community?.name ?: "Detalles de la comunidad") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Opciones", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
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
                            Divider()
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
                                    viewModel.deleteCommunity {
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
                                    model = community?.image,
                                    contentDescription = "Imagen de la comunidad",
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .clickable {
                                            navController.navigate(Screen.FullScreenImage.createRoute(community!!.image!!))
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
                                AssistChip(
                                    onClick = { },
                                    label = { Text(community!!.category!!) },
                                    enabled = false
                                )
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
                            Icon(Icons.Default.ExitToApp, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Salir de la comunidad")
                        }
                    }
                }

                // Members Section
                if (isMember || community?.is_public == true) {
                    item {
                        Text(
                            text = "Miembros",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    items(members) { member ->
                        val memberRole = communityMemberDisplayRole(
                            memberUserId = member.user_id,
                            creatorId = community?.creator_id,
                            rawRole = member.role
                        )
                        CommunityMemberItem(
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
    }

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
    var dailyMessageLimit by remember { mutableStateOf(community.daily_message_limit.toString()) }
    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                selectedImageBytes = context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() }
            } catch (e: Exception) {
                android.util.Log.e("EditCommunityDialog", "Error reading bytes", e)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar comunidad") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") })
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descripción") })
                OutlinedTextField(
                    value = dailyMessageLimit,
                    onValueChange = { dailyMessageLimit = it.filter { char -> char.isDigit() } },
                    label = { Text("Límite diario de mensajes") },
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
                    Switch(checked = onlyAdminsCanTalk, onCheckedChange = { onlyAdminsCanTalk = it })
                }
                Button(onClick = { launcher.launch("image/*") }) {
                    Text("Cambiar Foto")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val limit = dailyMessageLimit.toIntOrNull() ?: 0
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
    onPromote: () -> Unit,
    onDemote: () -> Unit,
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
                    if (role == "member") {
                        DropdownMenuItem(text = { Text("Promover a Admin") }, onClick = { showOptions = false; onPromote() })
                    } else if (role == "admin") {
                        DropdownMenuItem(text = { Text("Degradar a Miembro") }, onClick = { showOptions = false; onDemote() })
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
        else -> "member"
    }
}

private fun communityRoleLabel(role: String): String =
    when (normalizedCommunityRole(role)) {
        "owner", "creator" -> "Admin principal"
        "admin" -> "Admin"
        else -> "Miembro"
    }
