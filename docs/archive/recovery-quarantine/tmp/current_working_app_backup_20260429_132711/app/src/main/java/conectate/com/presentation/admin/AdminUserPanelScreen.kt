package conectate.com.presentation.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import conectate.com.data.model.User
import java.text.DecimalFormat



fun AdminUserPanelScreen(
    navController: NavController,
    viewModel: AdminUserPanelViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var showBalanceDialog by remember { mutableStateOf<User?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Panel de Usuarios") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadUsers() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refrescar")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Search Bar
            SearchBar(
                query = searchQuery,
                onQueryChange = { viewModel.searchUsers(it) },
                onSearch = { viewModel.searchUsers(it) },
                active = false,
                onActiveChange = {},
                placeholder = { Text("Buscar por nombre o ID...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.searchUsers("") }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {}

            Spacer(modifier = Modifier.height(8.dp))

            when (val state = uiState) {
                is AdminUserUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is AdminUserUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
                is AdminUserUiState.Success -> {
                    if (state.users.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No se encontraron usuarios")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            items(state.users) { user ->
                                UserAdminCard(
                                    user = user,
                                    onEditBalance = { showBalanceDialog = user }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Balance Edit Dialog
    showBalanceDialog?.let { user ->
        var balanceText by remember { mutableStateOf(user.balance.toString()) }

        AlertDialog(
            onDismissRequest = { showBalanceDialog = null },
            title = { Text("Editar Saldo") },
            text = {
                Column {
                    Text("Usuario: ${user.displayName}", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = balanceText,
                        onValueChange = { balanceText = it },
                        label = { Text("Nuevo Saldo") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        prefix = { Text("$ ") }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newBalance = balanceText.toDoubleOrNull() ?: user.balance
                        viewModel.updateUserBalance(user.id, newBalance)
                        showBalanceDialog = null
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBalanceDialog = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}


@Composable
fun UserAdminCard(
    user: User,
    onEditBalance: () -> Unit
) {
    val decimalFormat = remember { DecimalFormat("#,##0.00") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            AsyncImage(
                model = if (user.profileImageUrl.isNotEmpty()) user.profileImageUrl else "https://ui-avatars.com/api/?name=${user.displayName}",
                contentDescription = null,
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "@${user.username ?: "sin_usuario"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "ID: ${user.numeric_id ?: "N/A"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${decimalFormat.format(user.balance)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold
                )
                IconButton(onClick = onEditBalance) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Editar Saldo",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}







