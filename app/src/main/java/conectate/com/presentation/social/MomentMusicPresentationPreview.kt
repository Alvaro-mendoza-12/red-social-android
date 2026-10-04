package conectate.com.presentation.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import conectate.com.data.model.MomentMusicPresentation
import conectate.com.util.UbuntuMedia
import conectate.com.data.music.timedMusicLyrics
import conectate.com.data.music.activeMusicLyricIndex

/** The same measured layout in the picker, editor and published moment. */
@Composable
internal fun MomentMusicPresentationPreview(
    presentation: MomentMusicPresentation,
    title: String,
    artist: String?,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    rawLyrics: String? = null,
    playbackPositionMs: Long = 0L,
    durationMs: Long? = null,
) {
    if (presentation == MomentMusicPresentation.AUDIO_ONLY || title.isBlank()) return
    if (presentation == MomentMusicPresentation.LYRICS) {
        val cues = remember(rawLyrics, durationMs) { timedMusicLyrics(rawLyrics, durationMs) }
        val active = activeMusicLyricIndex(cues, playbackPositionMs)
        val next = if (active >= 0) cues.getOrNull(active + 1) else cues.firstOrNull { it.startMs > playbackPositionMs }
        BoxWithConstraints(modifier.widthIn(max = 340.dp).testTag("moment_music_presentation_lyrics")) {
            val lyricSize = (maxWidth.value * .075f).coerceIn(18f, 30f).sp
            Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (active > 0) Text(cues[active - 1].text, color = Color.White.copy(alpha = .48f),
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = lyricSize,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val current = cues.getOrNull(active)?.text
                Text(current ?: if (cues.isEmpty()) "Letra sincronizada no disponible" else "♫", color = Color.White,
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = lyricSize,
                    style = MaterialTheme.typography.titleLarge.copy(shadow = Shadow(Color.Black.copy(alpha = .65f), Offset(0f, 2f), 6f)),
                    textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("moment_music_active_lyric"))
                next?.text?.takeIf(String::isNotBlank)?.let { Text(it, color = Color.White.copy(alpha = .48f),
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = lyricSize,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        return
    }
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
