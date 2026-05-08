package conectate.com.presentation.ranking

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.components.UserItem
import conectate.com.presentation.components.PostCard
// VideoItem removed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingScreen(
    navController: NavController,
    viewModel: RankingViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Usuarios", "Videos", "Publicaciones")
    
    val users by viewModel.users.collectAsState()
    val videos by viewModel.videos.collectAsState()
    val posts by viewModel.posts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ranking") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            Box(modifier = Modifier.fillMaxSize()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        when (selectedTab) {
                            0 -> { // Users
                                items(users) { user ->
                                    UserItem(
                                        user = user,
                                        onClick = { navController.navigate(Screen.UserProfile.createRoute(user.id)) },
                                        trailingContent = {
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "${user.followers_count}",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "Seguidores",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                            }
                                        }
                                    )
                                    Divider()
                                }
                            }
                            1 -> { // Videos
                                items(videos) { video ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { navController.navigate(Screen.VideoPlayer.createRoute(video.id)) }
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Simple placeholder for video item
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = video.title,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                modifier = Modifier.padding(top = 4.dp)
                                            ) {
                                                Text(
                                                    text = "${video.views} vistas",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                                
                                                // Likes
                                                Text(
                                                    text = "${video.likeCount} Me gusta",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                                
                                                // Shares
                                                Text(
                                                    text = "${video.shareCount} Comp.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }
                                    }
                                    Divider()
                                }
                            }
                            2 -> { // Posts
                                items(posts) { post ->
                                    PostCard(
                                        post = post,
                                        onLikeClick = { /* No-op for now in ranking specific */ },
                                        onCommentClick = { navController.navigate(Screen.PostDetail.createRoute(post.id)) },
                                        onShareClick = { /* No-op */ },
                                        onUserClick = { navController.navigate(Screen.UserProfile.createRoute(post.userId)) },
                                        onPostClick = { navController.navigate(Screen.PostDetail.createRoute(post.id)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
