package conectate.com.presentation.profile

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.data.model.User

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowListScreen(
    navController: NavController,
    userId: String,
    type: String, // "followers" or "following"
    viewModel: FollowListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val title = when(type) {
        "followers" -> "Seguidores"
        "following" -> "Siguiendo"
        "friends" -> "Amigos"
        else -> "Lista"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
            when (val state = uiState) {
                is FollowListUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is FollowListUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = { viewModel.loadData() }) {
                            Text("Reintentar")
                        }
                    }
                }
                is FollowListUiState.Success -> {
                    if (state.users.isEmpty()) {
                        Text(
                            text = "Aún no hay usuarios aquí.",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(state.users) { user ->
                                conectate.com.presentation.components.UserItem(
                                    user = user,
                                    onClick = {
                                        // Navigate to user profile
                                        // If clicking on yourself, maybe go to own profile tab or just standard profile screen
                                        navController.navigate(Screen.UserProfile.createRoute(user.id))
                                    }
                                )
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}
