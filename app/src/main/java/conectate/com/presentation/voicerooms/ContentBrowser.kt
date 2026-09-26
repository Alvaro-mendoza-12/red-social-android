package conectate.com.presentation.voicerooms

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import conectate.com.data.repository.VoiceRoomRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentBrowser(
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onResolveUrl: suspend (String) -> VoiceRoomRepository.CinemaResolvedItem? = { null }
) {
    val context = LocalContext.current
    val catalog = remember { CinemaCatalog() }
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var manualUrl by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var searchResults by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var trendingContent by remember { mutableStateOf<Map<String, List<ContentItem>>>(emptyMap()) }
    var isSearching by remember { mutableStateOf(false) }
    var isResolving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        trendingContent = catalog.getTrending()
    }

    LaunchedEffect(searchQuery) {
        val requestedQuery = searchQuery.trim()
        if (requestedQuery.length < 2) {
            searchResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        delay(450)
        isSearching = true
        try {
            searchResults = catalog.searchUniversal(requestedQuery)
        } finally {
            isSearching = false
        }
    }

    fun selectCatalogItem(item: ContentItem) {
        if (isResolving) return
        scope.launch {
            isResolving = true
            try {
                val playableUrl = catalog.resolveStream(item)
                if (playableUrl != null) {
                    onSelect(playableUrl, item.title)
                } else {
                    Toast.makeText(
                        context,
                        "Este título no tiene un archivo reproducible disponible.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                isResolving = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F0F0F),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            OutlinedTextField(
                value = manualUrl,
                onValueChange = {
                    manualUrl = it.take(4_096)
                    manualError = null
                },
                placeholder = {
                    Text(
                        "Enlace directo autorizado (MP4, HLS, DASH, Drive)",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                isError = manualError != null,
                supportingText = manualError?.let { error ->
                    { Text(error) }
                },
                trailingIcon = {
                    if (manualUrl.isNotBlank()) {
                        if (isResolving) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(
                                onClick = {
                                    val validation = CinemaSourcePolicy.validateManualUrl(manualUrl)
                                    if (!validation.isValid) {
                                        manualError = validation.error
                                        return@IconButton
                                    }
                                    scope.launch {
                                        isResolving = true
                                        try {
                                            val resolved = runCatching {
                                                onResolveUrl(validation.normalizedUrl.orEmpty())
                                            }.getOrNull()
                                            val resolvedUrl = resolved?.url.orEmpty()
                                            when {
                                                resolved == null -> manualError = "El servidor no respondió. Inténtalo de nuevo."
                                                resolved.error != null -> manualError = resolved.error
                                                !CinemaSourcePolicy.isResolvedPlaybackUrl(resolvedUrl) -> {
                                                    manualError = "El servidor devolvió un enlace no reproducible."
                                                }
                                                else -> onSelect(resolvedUrl, resolved.title.ifBlank { "Video externo" })
                                            }
                                        } finally {
                                            isResolving = false
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Resolver y reproducir enlace",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color(0xFF151515),
                    focusedContainerColor = Color(0xFF151515),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            )

            Text(
                text = "Comparte únicamente contenido propio, autorizado o de dominio público. No se aceptan páginas web, torrents ni enlaces locales.",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it.take(100) },
                placeholder = {
                    Text("Buscar cine de dominio público...", color = Color.Gray, fontSize = 14.sp)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Buscar",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Limpiar búsqueda",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color(0xFF1A1A1A),
                    focusedContainerColor = Color(0xFF1A1A1A),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            if (searchQuery.trim().length >= 2) {
                if (!isSearching && searchResults.isEmpty()) {
                    Text(
                        "No se encontraron títulos reproducibles.",
                        color = Color.Gray,
                        modifier = Modifier.padding(20.dp)
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(searchResults, key = { it.providerId }) { item ->
                        ContentCard(item = item, onClick = { selectCatalogItem(item) })
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        Text(
                            text = "Explora contenido verificable",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    trendingContent.forEach { (category, content) ->
                        item(key = "title-$category") {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                        item(key = "row-$category") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(content, key = { it.providerId }) { item ->
                                    ContentCard(item = item, onClick = { selectCatalogItem(item) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContentCard(item: ContentItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .height(180.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1A))
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    "DOMINIO PÚBLICO",
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 2,
            fontWeight = FontWeight.Medium
        )
    }
}

data class ContentItem(
    val title: String,
    val posterUrl: String,
    val videoUrl: String? = null,
    val description: String = "",
    val provider: String = "manual",
    val providerId: String = ""
)
