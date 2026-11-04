package conectate.com.presentation.ranking

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    val usersMetric by viewModel.usersMetric.collectAsState()
    val myRank by viewModel.myRank.collectAsState()
    val loadError by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ranking") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            if (selectedTab == 0) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("followers" to "Seguidores", "following" to "Siguiendo", "posts" to "Publicaciones").forEach { (metric, label) ->
                        FilterChip(selected = usersMetric == metric, onClick = { viewModel.selectUsersMetric(metric) }, label = { Text(label) })
                    }
                }
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text("Los 100 primeros · " + (myRank?.let { "Tu puesto: #$it" } ?: "Tu puesto aún no está disponible"),
                        Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            loadError?.let { message ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::loadData) { Text("Reintentar") }
                }
            }
            if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            
            Box(modifier = Modifier.fillMaxSize()) {
                if (isLoading && users.isEmpty() && videos.isEmpty() && posts.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        when (selectedTab) {
                            0 -> { // Users
                                itemsIndexed(users, key = { _, user -> user.id }) { index, user ->
                                    Text("#${index + 1}", Modifier.padding(start = 16.dp, top = 8.dp),
                                        style = MaterialTheme.typography.labelLarge)
                                    UserItem(
                                        user = user,
                                        onClick = { navController.navigate(Screen.UserProfile.createRoute(user.id)) },
                                        trailingContent = {
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = when (usersMetric) {
                                                        "posts" -> "${user.post_count ?: 0}"
                                                        "following" -> "${user.following_count ?: 0}"
                                                        else -> "${user.followers_count ?: 0}"
                                                    },
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = when (usersMetric) { "posts" -> "Publicaciones"; "following" -> "Siguiendo"; else -> "Seguidores" },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                            }
                                        }
                                    )
                                    HorizontalDivider()
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
                                    HorizontalDivider()
                                }
                            }
                            2 -> { // Posts
                                items(posts) { post ->
                                    PostCard(
                                        post = post,
                                        onLikeClick = { viewModel.togglePostLike(post) },
                                        onReactionSelect = { reaction ->
                                            viewModel.setPostReaction(post, reaction)
                                        },
                                        onReactionSummaryClick = {
                                            navController.navigate(Screen.PostDetail.createRoute(post.id))
                                        },
                                        onCommentClick = { navController.navigate(Screen.PostDetail.createRoute(post.id)) },
                                        onShareClick = { viewModel.togglePostShare(post) },
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
