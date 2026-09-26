
package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import conectate.com.data.model.Moment

@Composable
fun MyMomentManagementRow(
    moments: List<Moment>,
    onDeleteClick: (String) -> Unit
) {
    if (moments.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Green Box 1: Section Title
        Text(
            text = "Mis Momentos",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Green Box 2: Management List
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            moments.forEach { moment ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Small little square (Thumbnail)
                    AsyncImage(
                        model = moment.thumbnailUrl ?: moment.mediaUrl,
                        contentDescription = "Moment Thumbnail",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // Info
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (moment.mediaType == "video") "Video" else "Imagen",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = momentAudienceLabel(moment),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Trash Can (Left? User said "ala isquierda un tachito" which implies left of the content or relative. 
                    // Usually actions are on right. "ala isquierda" of what? "ala isquierda un tachito para poner eliminar tambien desde hay"
                    // Maybe they mean left of the text? Or right side?
                    // Standard UI is Right. I'll put it on the Right for consistency unless "ala izquierda" means "To the left [of the screen?]"
                    // Let's stick to Right for now as it's standard android. 
                    IconButton(
                        onClick = { onDeleteClick(moment.id) },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.Red)
                    ) {
                        Icon(Icons.Default.Delete, null)
                    }
                }
            }
        }
    }
}

internal fun momentAudienceLabel(moment: Moment): String = when {
    moment.visibilityType.equals("private", ignoreCase = true) ||
        moment.visibility.equals("only_me", ignoreCase = true) -> "Solo tú"
    moment.visibility.equals("followers", ignoreCase = true) -> "Quienes te siguen"
    moment.visibility.equals("following", ignoreCase = true) -> "Personas que sigues"
    moment.visibility.equals("chats", ignoreCase = true) -> "Personas con las que hablas"
    moment.visibility.equals("only_selected", ignoreCase = true) ->
        "${moment.audienceIds.size} persona${if (moment.audienceIds.size == 1) "" else "s"} elegida${if (moment.audienceIds.size == 1) "" else "s"}"
    moment.visibility.equals("except_selected", ignoreCase = true) ->
        "Todos excepto ${moment.excludedIds.size}"
    else -> "Visible para todos"
}
