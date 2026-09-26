package conectate.com.presentation.social

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.model.Post
import conectate.com.data.model.User
import conectate.com.data.model.Video
import conectate.com.presentation.components.CircularProfileImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingScreen(
    navController: NavController,
    viewModel: RankingViewModel = hiltViewModel()
) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Videos", "Usuarios", "Publicaciones")
    
    val topVideos by viewModel.topVideos.collectAsState()
    val topUsers by viewModel.topUsers.collectAsState()
    val topPosts by viewModel.topPosts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ranking Global", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            if (isLoading && topVideos.isEmpty() && topUsers.isEmpty() && topPosts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                when (selectedTabIndex) {
                    0 -> VideoRanking(topVideos)
                    1 -> UserRanking(topUsers)
                    2 -> PostRanking(topPosts)
                }
            }
        }
    }
}

@Composable
fun VideoRanking(videos: List<Video>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(videos) { index, video ->
            ListItem(
                headlineContent = { Text(video.title.ifBlank { "Video #${index + 1}" }, fontWeight = FontWeight.Bold) },
                supportingContent = { Text("${video.likeCount} likes • ${video.commentCount} comentarios") },
                leadingContent = {
                    Text(
                        text = "#${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (index < 3) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.width(32.dp)
                    )
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun UserRanking(users: List<User>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(users) { index, user ->
            ListItem(
                headlineContent = { Text(user.username.orEmpty().ifBlank { "Usuario ${index + 1}" }) },
                supportingContent = { Text("${user.followers_count ?: 0} seguidores") },
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.width(32.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        CircularProfileImage(
                            imageUrl = user.avatar_url ?: "",
                            size = 40.dp
                        )
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun PostRanking(posts: List<Post>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(posts) { index, post ->
            ListItem(
                headlineContent = { Text(post.userName.ifBlank { "Post #${index + 1}" }) },
                supportingContent = { Text("${post.shareCount} compartidos • ${post.likeCount} reacciones") },
                leadingContent = {
                    Text(
                        text = "#${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.width(32.dp)
                    )
                }
            )
            HorizontalDivider()
        }
    }
}
