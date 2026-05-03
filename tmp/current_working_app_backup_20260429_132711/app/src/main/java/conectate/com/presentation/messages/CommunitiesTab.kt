package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController

/**
 * Tab de Comunidades (NUEVO - Fase 3)
 */
@Composable
fun CommunitiesTab(
    navController: NavController,
    viewModel: CommunitiesViewModel = hiltViewModel(),
    onNavigateToCommunityChat: (String) -> Unit
) {
    val communitiesState by viewModel.communitiesState.collectAsState()
    val combinedCommunities by viewModel.combinedCommunities.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Search Bar with Loading Indicator
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Buscar todas las comunidades") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar"
                    )
                },
                trailingIcon = {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                },
                singleLine = true,
                shape = CircleShape
            )
        }

        when (val state = communitiesState) {
            is CommunitiesUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is CommunitiesUiState.Success -> {
                if (combinedCommunities.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = "Comunidades",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Text(
                                text = if (searchQuery.isEmpty()) "No hay comunidades disponibles" else "No se encontraron comunidades",
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(combinedCommunities) { communityWithLastMessage ->
                            CommunityItem(
                                communityWithLastMessage = communityWithLastMessage,
                                onClick = {
                                    // Navigate to Community Chat
                                    onNavigateToCommunityChat(communityWithLastMessage.community.id)
                                }
                            )
                            Divider()
                        }
                    }
                }
            }
            is CommunitiesUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = state.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
