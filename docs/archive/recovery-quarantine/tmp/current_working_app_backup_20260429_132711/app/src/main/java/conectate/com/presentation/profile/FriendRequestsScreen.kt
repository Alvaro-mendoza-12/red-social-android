package conectate.com.presentation.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.presentation.components.UserItem
import androidx.compose.material3.SwipeToDismiss
import androidx.compose.material3.rememberDismissState
import androidx.compose.material3.DismissValue
import androidx.compose.material3.DismissDirection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendRequestsScreen(
    navController: NavController,
    viewModel: FriendRequestsViewModel = hiltViewModel()
) {
    val requests by viewModel.requests.collectAsState()
    val acceptedRequests by viewModel.acceptedRequests.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Solicitudes de Amistad") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading && requests.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (requests.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tienes solicitudes pendientes", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(requests, key = { it.id }) { user ->
                        val acceptedTimestamp = acceptedRequests[user.id]
                        val isConfirmed = acceptedTimestamp != null
                        
                        // SwipeToDismiss only for confirmed items
                        if (isConfirmed) {
                             val dismissState = rememberDismissState(
                                confirmValueChange = {
                                    if (it == DismissValue.DismissedToStart || it == DismissValue.DismissedToEnd) {
                                        viewModel.dismissConfirmedRequest(user.id)
                                        true
                                    } else false
                                }
                            )
                            
                            SwipeToDismiss(
                                state = dismissState,
                                background = {
                                    val color = Color.Red
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = if (dismissState.dismissDirection == DismissDirection.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                                    ) {
                                        Text("Eliminar", color = Color.White)
                                    }
                                },
                                dismissContent = {
                                    UserItem(
                                        user = user,
                                        onClick = { navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(user.id)) },
                                        trailingContent = {
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "Confirmado",
                                                    color = MaterialTheme.colorScheme.primary,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                                )
                                                // Format timestamp simply for now
                                                val date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date(acceptedTimestamp!!))
                                                Text(
                                                    text = date,
                                                    color = Color.Gray,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    )
                                }
                            )
                        } else {
                            UserItem(
                                user = user,
                                onClick = { navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(user.id)) },
                                trailingContent = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.acceptRequest(user) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Text("Confirmar")
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.rejectRequest(user) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Text("Eliminar")
                                        }
                                    }
                                }
                            )
                        }
                        Divider()
                    }
                }
            }
        }
    }
}
