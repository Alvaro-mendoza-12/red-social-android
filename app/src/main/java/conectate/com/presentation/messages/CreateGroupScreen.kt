package conectate.com.presentation.messages

import androidx.compose.runtime.Composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.dp
import androidx.compose.animation.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import conectate.com.data.model.User
import conectate.com.presentation.components.CircularProfileImage

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun CreateGroupScreen(
    navController: NavController,
    viewModel: CreateGroupViewModel = hiltViewModel()
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val selectedUsers by viewModel.selectedUsers.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val selectedImageBytes by viewModel.selectedImageBytes.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var groupDescription by remember { mutableStateOf("") }

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            val bytes = context.contentResolver.openInputStream(it)?.readBytes()
            viewModel.setImageBytes(bytes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Nuevo grupo",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        )
                        Text(
                            text = if (currentStep == GroupCreationStep.SELECT_MEMBERS) {
                                if (selectedUsers.isEmpty()) "Añadir participantes" else "${selectedUsers.size} seleccionado(s)"
                            } else {
                                "Añadir asunto"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentStep == GroupCreationStep.SELECT_MEMBERS) {
                            navController.popBackStack()
                        } else {
                            viewModel.previousStep()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            if (selectedUsers.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        if (currentStep == GroupCreationStep.SELECT_MEMBERS) {
                            viewModel.nextStep()
                        } else {
                            if (groupName.isNotBlank()) {
                                viewModel.createGroup(groupName.trim(), groupDescription.trim()) { chatId ->
                                    navController.popBackStack()
                                }
                            } else {
                                android.widget.Toast.makeText(context, "Ingresa un nombre para el grupo", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            imageVector = if (currentStep == GroupCreationStep.SELECT_MEMBERS) {
                                Icons.Default.ArrowForward
                            } else {
                                Icons.Default.Check
                            },
                            contentDescription = if (currentStep == GroupCreationStep.SELECT_MEMBERS) "Siguiente" else "Crear"
                        )
                    }
                }
            }
        }
    ) { padding ->
        androidx.compose.animation.AnimatedContent(
            targetState = currentStep,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            transitionSpec = {
                if (targetState == GroupCreationStep.ENTER_DETAILS) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith
                            (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "StepTransition"
        ) { step ->
            when (step) {
                GroupCreationStep.SELECT_MEMBERS -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Search
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                viewModel.searchUsers(it)
                            },
                            placeholder = { Text("Buscar usuarios...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (isSearching) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                }
                            },
                            singleLine = true,
                            shape = CircleShape
                        )

                        // Selected Horizontal list (WhatsApp Style)
                        if (selectedUsers.isNotEmpty()) {
                            androidx.compose.foundation.lazy.LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(selectedUsers) { user ->
                                    Box(contentAlignment = Alignment.TopEnd) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            CircularProfileImage(imageUrl = user.avatar_url, size = 50.dp)
                                            Text(
                                                text = user.displayName?.split(" ")?.firstOrNull() ?: "Usuario",
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.width(60.dp),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                        Surface(
                                            onClick = { viewModel.toggleUserSelection(user) },
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = CircleShape,
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = null,
                                                modifier = Modifier.padding(4.dp),
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                            Divider()
                        }

                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(searchResults) { user ->
                                UserSelectionItem(
                                    user = user,
                                    isSelected = selectedUsers.any { it.id == user.id },
                                    onToggle = { viewModel.toggleUserSelection(user) }
                                )
                            }
                        }
                    }
                }
                GroupCreationStep.ENTER_DETAILS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Image Picker
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { launcher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedImageBytes != null) {
                                AsyncImage(
                                    model = selectedImageBytes,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AddAPhoto,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedTextField(
                            value = groupName,
                            onValueChange = { if (it.length <= 25) groupName = it },
                            label = { Text("Asunto del grupo") },
                            placeholder = { Text("Máx. 25 caracteres") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                Text(
                                    text = "${25 - groupName.length}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = groupDescription,
                            onValueChange = { groupDescription = it },
                            label = { Text("Descripción (opcional)") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Proporciona un asunto y un icono opcional.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        if (errorMessage != null) {
                            Text(
                                text = errorMessage!!,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 16.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserSelectionItem(user: User, isSelected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            CircularProfileImage(imageUrl = user.avatar_url, size = 48.dp)
            if (isSelected) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(18.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.displayName ?: "Usuario",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            )
            Text(
                text = user.email ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
