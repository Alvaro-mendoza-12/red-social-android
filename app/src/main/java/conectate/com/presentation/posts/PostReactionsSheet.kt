package conectate.com.presentation.posts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.Post
import conectate.com.presentation.components.CircularProfileImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostReactionsSheet(post: Post, state: PostReactionUsersState, selected: String?,
    onFilter: (String?) -> Unit, onRetry: () -> Unit, onUser: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        PostReactionUsersContent(post, state, selected, onFilter, onRetry, onUser)
    }
}

@Composable
internal fun PostReactionUsersContent(post: Post, state: PostReactionUsersState, selected: String?,
    onFilter: (String?) -> Unit, onRetry: () -> Unit, onUser: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        Text("Reacciones", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        val total = post.reactionCounts.values.sum().takeIf { it > 0 } ?: post.likeCount
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = selected == null, onClick = { onFilter(null) }, label = { Text("Todas $total") }) }
            items(post.reactionCounts.filterValues { it > 0 }.toList()) { (type, count) ->
                FilterChip(selected = selected == type, onClick = { onFilter(type) }, label = { Text("${reactionEmoji(type)} $count") })
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> Column(Modifier.padding(horizontal = 20.dp)) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Reintentar") }
            }
            state.users.isEmpty() -> Text("Todavía no hay reacciones visibles.", modifier = Modifier.padding(20.dp))
            else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(state.users, key = { it.userId }) { user ->
                    Row(Modifier.fillMaxWidth().clickable { onUser(user.userId) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProfileImage(ownerId = user.userId, imageUrl = user.userAvatarUrl.orEmpty(), size = 42.dp,
                            isVerified = user.isVerified, fallbackName = user.userName)
                        Text(user.userName, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        Text(reactionEmoji(user.reactionType))
                    }
                }
            }
        }
    }
}
private fun reactionEmoji(type: String) = when(type) {
    "LOVE" -> "❤️"; "HAHA" -> "😂"; "WOW" -> "😮"; "SAD" -> "😢"; "ANGRY" -> "😡"; "CARE" -> "🤗"; "FIRE" -> "🔥"; else -> "👍"
}
