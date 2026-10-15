package conectate.com.presentation.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import conectate.com.data.model.Moment
import conectate.com.data.model.resolvedMusicAudioUrl
import conectate.com.presentation.components.CircularProfileImage

/** Author and soundtrack share measured layout, never independent screen offsets. */
@Composable
internal fun MomentAuthorHeader(
    moment: Moment,
    timestamp: String,
    onOpenMusicAttribution: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProfileImage(ownerId = moment.userId, 
                imageUrl = moment.userAvatarUrl, size = 40.dp, showBorder = false,
                fallbackName = moment.userDisplayName ?: moment.username,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    moment.userDisplayName ?: moment.username ?: "Usuario",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(timestamp, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        MomentMusicAttributionChip(moment, onOpenMusicAttribution)
    }
}

@Composable
private fun MomentMusicAttributionChip(moment: Moment, onOpenAttribution: (String) -> Unit) {
    val title = moment.musicTitle?.takeIf { it.isNotBlank() } ?: return
    if (moment.resolvedMusicAudioUrl.isNullOrBlank()) return
    val destination = moment.musicLicenseUrl?.takeIf { it.startsWith("https://") }
        ?: moment.musicSourceUrl?.takeIf { it.startsWith("https://") }
    Surface(
        modifier = Modifier.widthIn(max = 300.dp).testTag("moment_music_attribution")
            .clickable(enabled = destination != null) { destination?.let(onOpenAttribution) },
        color = Color.Black.copy(alpha = 0.68f),
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                "$title${moment.musicArtist?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}",
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (destination != null) {
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Default.Info, "Atribución y licencia", modifier = Modifier.size(14.dp))
            }
        }
    }
}
