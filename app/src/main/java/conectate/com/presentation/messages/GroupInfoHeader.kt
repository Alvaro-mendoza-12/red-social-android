package conectate.com.presentation.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.presentation.components.CircularProfileImage

/** The same protected image request and visible fallback used by other identities. */
@Composable
internal fun GroupInfoHeader(
    name: String,
    description: String?,
    imageUrl: String?,
    memberCount: Int,
    onOpenImage: (String) -> Unit,
) {
    val image = remember(imageUrl, UbuntuStorageUrl.baseUrl) { UbuntuStorageUrl.rewrite(imageUrl) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.testTag("group_info_avatar").semantics { contentDescription = "Foto del grupo" }
                .clickable(enabled = image != null) { image?.let(onOpenImage) }) {
                CircularProfileImage(
                    imageUrl = image,
                    size = 100.dp,
                    fallbackName = name,
                    showMomentRing = false,
                    openMomentOnClick = false,
                )
            }
            Text(name, style = MaterialTheme.typography.headlineSmall)
            description?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${memberCount.coerceAtLeast(0)} miembros", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
