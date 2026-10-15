package conectate.com.presentation.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import conectate.com.data.model.LiveStream
import conectate.com.presentation.components.CircularProfileImage

@Composable
fun LiveStreamsCarousel(
    streams: List<LiveStream>,
    onOpenLive: (String) -> Unit,
    onOpenAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "EN DIRECTO",
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedButton(
                onClick = onOpenAll,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Ver todos")
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
        ) {
            items(streams.take(20), key = { it.id }) { stream ->
                LiveCarouselCard(stream = stream, onClick = { onOpenLive(stream.id) })
            }
        }
    }
}

@Composable
private fun LiveCarouselCard(
    stream: LiveStream,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(width = 220.dp, height = 88.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ringBrush = Brush.sweepGradient(
                listOf(Color(0xFFFF2D55), Color(0xFFFFC107), Color(0xFF7C4DFF), Color(0xFFFF2D55))
            )

            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .then(Modifier)
                    .padding(0.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    border = BorderStroke(2.dp, ringBrush),
                    color = Color.Transparent,
                    shape = CircleShape
                ) {
                    Box(modifier = Modifier.padding(3.dp)) {
                        if (!stream.hostAvatarUrl.isNullOrBlank()) {
                            CircularProfileImage(ownerId = stream.hostUserId, imageUrl = stream.hostAvatarUrl, size = 48.dp)
                        } else {
                            Box(modifier = Modifier.size(48.dp).clip(CircleShape))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stream.title.ifBlank { "EN DIRECTO" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${stream.viewerCount} viendo · ${stream.hostName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

