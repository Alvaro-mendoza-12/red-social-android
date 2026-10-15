package conectate.com.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import conectate.com.data.model.User
import conectate.com.data.model.profilePublicHandle

@Composable
fun MentionSuggestions(users: List<User>, onSelected: (User) -> Unit, modifier: Modifier = Modifier) {
    if (users.isEmpty()) return
    Surface(modifier.fillMaxWidth().testTag("mention_suggestions"), tonalElevation = 3.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.heightIn(max = 192.dp).verticalScroll(rememberScrollState())) {
            users.take(6).forEach { user ->
                Row(Modifier.fillMaxWidth().clickable { onSelected(user) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProfileImage(ownerId = user.id, imageUrl = user.avatar_url.orEmpty(), size = 32.dp,
                        isVerified = user.is_verified, openMomentOnClick = false)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.displayName, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${profilePublicHandle(user)}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
