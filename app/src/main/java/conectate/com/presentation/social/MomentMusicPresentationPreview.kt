package conectate.com.presentation.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import conectate.com.data.model.MomentMusicPresentation
import conectate.com.util.UbuntuMedia

/** The same measured layout in the picker, editor and published moment. */
@Composable
internal fun MomentMusicPresentationPreview(
    presentation: MomentMusicPresentation,
    title: String,
    artist: String?,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    if (presentation == MomentMusicPresentation.AUDIO_ONLY || title.isBlank()) return
    val card = presentation == MomentMusicPresentation.CARD
    Surface(
        modifier.widthIn(max = if (card) 240.dp else 300.dp)
            .testTag("moment_music_presentation_${presentation.wireValue}"),
        color = Color(0xFFF7F4FF), contentColor = Color(0xFF201A2B),
        shape = RoundedCornerShape(if (card) 22.dp else 16.dp),
        tonalElevation = 3.dp,
    ) {
        if (card) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MomentMusicArtwork(artworkUrl, Modifier.size(136.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                artist?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MomentMusicArtwork(artworkUrl, Modifier.size(42.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    artist?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentMusicArtwork(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFE4DCF3)),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, null, tint = Color(0xFF635089), modifier = Modifier.size(28.dp))
        if (!url.isNullOrBlank()) AsyncImage(
            model = UbuntuMedia.resolveUrl(url) ?: url, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
    }
}
