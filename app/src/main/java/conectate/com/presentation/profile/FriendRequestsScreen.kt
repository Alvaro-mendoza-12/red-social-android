package conectate.com.presentation.profile

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.presentation.components.UserItem

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
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

                        if (isConfirmed) {
                            UserItem(
                                user = user,
                                onClick = { navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(user.id)) },
                                trailingContent = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "Confirmado",
                                                    color = MaterialTheme.colorScheme.primary,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            val date = java.text.SimpleDateFormat(
                                                "dd/MM/yyyy",
                                                androidx.compose.ui.platform.LocalLocale.current.platformLocale,
                                            ).format(java.util.Date(acceptedTimestamp!!))
                                            Text(
                                                text = date,
                                                color = Color.Gray,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.dismissConfirmedRequest(user.id) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Eliminar",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
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
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
