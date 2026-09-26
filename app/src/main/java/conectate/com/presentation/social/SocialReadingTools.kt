package conectate.com.presentation.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import conectate.com.data.model.Post
import java.text.Normalizer
import java.util.Locale

internal object SocialReadingPolicy {
    fun minutes(post: Post): Int {
        val words = post.content.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return ((words + 179) / 180).coerceAtLeast(1)
    }
    fun select(posts: List<Post>, query: String, maximumMinutes: Int): List<Post> {
        val terms = normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        return posts.distinctBy { it.id }.filter { post ->
            val searchable = normalize("${post.userName} ${post.content}")
            post.content.isNotBlank() && (maximumMinutes <= 0 || minutes(post) <= maximumMinutes) &&
                terms.all(searchable::contains)
        }
    }
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SocialReadingTools(posts: List<Post>, onOpenPost: (String) -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableIntStateOf(0) }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("Mi lectura · buscar y elegir por tiempo")
    }
    if (show) ModalBottomSheet(onDismissRequest = { show = false }) {
        val selected = remember(posts, query, minutes) { SocialReadingPolicy.select(posts, query, minutes) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Mi lectura", style = MaterialTheme.typography.titleLarge)
            Text("Busca entre las publicaciones ya cargadas. Tiempo estimado del texto; no incluye videos.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it.take(120) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Tema o autor") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 3).forEach { limit ->
                    FilterChip(selected = minutes == limit, onClick = { minutes = limit },
                        label = { Text(if (limit == 0) "Todo" else "Hasta $limit min") })
                }
            }
            Text("${selected.size} publicaciones", style = MaterialTheme.typography.labelMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (selected.isEmpty()) item { Text("No hay textos que coincidan. Cambia el tema o carga más publicaciones en Social.") }
                items(selected, key = { it.id }) { post ->
                    TextButton(onClick = { show = false; onOpenPost(post.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("${post.userName} · ${SocialReadingPolicy.minutes(post)} min aprox.", style = MaterialTheme.typography.labelLarge)
                            Text(post.content, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
