package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import conectate.com.data.model.User

@Composable

fun UserItem(
    user: User,
    onClick: () -> Unit,
    showPublicId: Boolean = false,
    trailingContent: @Composable (RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProfileImage(
            imageUrl = user.avatar_url,
            size = 56.dp,
            isVerified = user.is_verified,
            fallbackName = user.displayName
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.display_name ?: user.full_name ?: "Usuario",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                
                if (user.is_verified) {
                    Spacer(modifier = Modifier.width(4.dp))
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.CheckCircle,
                        contentDescription = "Verificado",
                        tint = androidx.compose.ui.graphics.Color(0xFF1DA1F2),
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (user.is_admin || isUserAdmin(user.username, user.email)) {
                    Spacer(modifier = Modifier.width(6.dp))
                    AdminBadge(showVerifiedLine = false)
                }
            }
            
            val handle = if (!user.username.isNullOrBlank()) {
                "@${user.username}"
            } else {
                "@usuario${user.numeric_id ?: ""}"
            }
            
            Text(
                text = if (showPublicId) {
                    val publicId = user.numeric_id?.toString()?.takeIf { it.isNotBlank() }
                        ?: user.id.take(8)
                    "$handle · ID $publicId"
                } else handle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        }
    }
}
