package conectate.com.presentation.messages

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var selectedSection by rememberSaveable { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                placeholder = { Text("Buscar comunidades", maxLines = 1) },
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
            CollectionQrScannerButton(navController = navController)
        }

        when (val state = communitiesState) {
            is CommunitiesUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is CommunitiesUiState.Success -> {
                val myCommunities = combinedCommunities.filter { it.community.is_member }
                val exploreCommunities = combinedCommunities.filter { !it.community.is_deleted }
                val visibleCommunities = if (selectedSection == 0) myCommunities else exploreCommunities

                SecondaryTabRow(
                    selectedTabIndex = selectedSection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedSection == 0,
                        onClick = { selectedSection = 0 },
                        text = { Text("Mis comunidades (${myCommunities.size})") }
                    )
                    Tab(
                        selected = selectedSection == 1,
                        onClick = { selectedSection = 1 },
                        text = { Text("Explorar (${exploreCommunities.size})") }
                    )
                }

                if (visibleCommunities.isEmpty()) {
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
                                text = when {
                                    searchQuery.isNotBlank() -> "No se encontraron comunidades"
                                    selectedSection == 0 -> "No perteneces a ninguna comunidad"
                                    else -> "No hay comunidades para explorar"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(
                                text = if (selectedSection == 0) "Mis comunidades" else "Explorar comunidades",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(visibleCommunities) { communityWithLastMessage ->
                            CommunityItem(
                                communityWithLastMessage = communityWithLastMessage,
                                currentUserId = viewModel.currentUserId,
                                onClick = {
                                    onNavigateToCommunityChat(communityWithLastMessage.community.id)
                                }
                            )
                            HorizontalDivider()
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
