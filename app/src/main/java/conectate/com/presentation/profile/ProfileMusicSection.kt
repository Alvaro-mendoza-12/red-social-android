package conectate.com.presentation.profile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.model.User
import conectate.com.data.model.ProfileMusicSummary
import conectate.com.data.model.profileMusicReactions
import conectate.com.presentation.components.CommentAudioPlayer

@Composable
internal fun ProfileMusicSection(user: User, viewModel: ProfileMusicViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(user.id, user.favorite_music_url) {
        viewModel.attach(user.id, user.favorite_music_url.orEmpty())
        onStopOrDispose { viewModel.detach() }
    }
    if (state.available) {
        ProfileMusicContent(state.summary?.musicUrl ?: user.favorite_music_url.orEmpty(), state.summary, state.error,
            viewModel::react, viewModel::presentation, viewModel::refresh)
    } else TextButton(onClick = viewModel::refresh) { Text(state.error ?: "Música no disponible") }
}

@Composable
internal fun ProfileMusicContent(
    url: String, summary: ProfileMusicSummary?, error: String?,
    onReact: (String) -> Unit, onPresentation: (String) -> Unit, onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (summary?.canEditPresentation == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("audio" to "Solo audio", "disc" to "Disco").forEach { (value, label) ->
                    FilterChip(selected = summary.presentation == value,
                        onClick = { onPresentation(value) }, label = { Text(label) })
                }
            }
        }
        CommentAudioPlayer(audioUrl = url, modifier = Modifier.fillMaxWidth(), showAsDisc = summary?.presentation == "disc")
        if (summary != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profileMusicReactions.forEach { (reaction, emoji) ->
                    val count = summary.counts[reaction] ?: 0
                    FilterChip(selected = summary.reaction == reaction, onClick = { onReact(reaction) },
                        modifier = Modifier.semantics { contentDescription = "$reaction: $count" },
                        label = { Text("$emoji $count") })
                }
            }
            Text("${summary.total} reacciones", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (error != null) TextButton(onClick = onRetry) { Text(error) }
    }
}
