package conectate.com.presentation.voicerooms

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.compose.foundation.text.KeyboardActions
import conectate.com.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentBrowser(
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onResolveUrl: suspend (String) -> conectate.com.data.repository.VoiceRoomRepository.CinemaResolvedItem? = { null }
) {
    val context = LocalContext.current
    val tmdbApiKey = BuildConfig.TMDB_API_KEY
    val catalog = remember { CinemaCatalog(tmdbApiKey) }
    
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var manualUrl by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    
    var trendingContent by remember { mutableStateOf<Map<String, List<ContentItem>>>(emptyMap()) }
    var selectedCategory by remember { mutableStateOf("all") }
    
    var selectedSeries by remember { mutableStateOf<ContentItem?>(null) }
    var selectedSeason by remember { mutableStateOf<Season?>(null) }

    LaunchedEffect(Unit) {
        trendingContent = catalog.getTrending()
    }

    // v14.0: Incremental search logic
    val triggerSearch: (String, String) -> Unit = { query, cat ->
        scope.launch {
            if (query.length >= 2) {
                isSearching = true
                searchResults = catalog.searchUniversal(query, cat)
                isSearching = false
            } else {
                searchResults = emptyList()
            }
        }
    }

    // v14.0: Debounce effect
    LaunchedEffect(searchQuery, selectedCategory) {
        if (searchQuery.length >= 2) {
            delay(500)
            triggerSearch(searchQuery, selectedCategory)
        } else if (searchQuery.isEmpty()) {
            searchResults = emptyList()
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
            // Manual URL Input (Legal Compliance)
            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                placeholder = { Text("Paste Video Link (YouTube, Drive, RD, IPTV, MP4...)", color = Color.Gray, fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (manualUrl.isNotEmpty()) {
                        IconButton(onClick = { 
                             scope.launch {
                                 isSearching = true
                                 val resolved = onResolveUrl(manualUrl)
                                 isSearching = false
                                 if (resolved != null && resolved.error == null) {
                                     onSelect(resolved.url, resolved.title)
                                 } else {
                                     // Fallback
                                     onSelect(manualUrl, "Video Externo")
                                 }
                             }
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color(0xFF151515),
                    focusedContainerColor = Color(0xFF151515),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(0.5f)
                )
            )
            
            Text(
                text = "💡 Tip: Copia el 'Link del Video' directamente (que termine en .mp4 o .m3u8). Los links de páginas web no funcionan.",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )


            // v14.0: Incremental search logic (controlled by top-level LaunchedEffect)


            OutlinedTextField(
                value = searchQuery,
                onValueChange = { 
                    searchQuery = it
                },
                placeholder = { Text("Buscar películas, series, anime...", color = Color.Gray, fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = Color.Gray, modifier = Modifier.size(18.dp))
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

            // v9.0: Category Tabs
            ScrollableTabRow(
                selectedTabIndex = when(selectedCategory) { "all" -> 0; "anime" -> 1; "movie" -> 2; "tv" -> 3; "drama" -> 4; else -> 0 },
                containerColor = Color.Transparent,
                edgePadding = 16.dp,
                divider = {},
                indicator = { TabRowDefaults.Indicator(modifier = Modifier.tabIndicatorOffset(it[when(selectedCategory) { "all" -> 0; "anime" -> 1; "movie" -> 2; "tv" -> 3; "drama" -> 4; else -> 0 }]), color = MaterialTheme.colorScheme.primary) }
            ) {
                listOf("Todo" to "all", "Anime" to "anime", "Películas" to "movie", "Series" to "tv", "Dramas" to "drama").forEach { (label, cat) ->
                    Tab(
                        selected = selectedCategory == cat,
                        onClick = { 
                            selectedCategory = cat
                            if (searchQuery.length >= 2) triggerSearch(searchQuery, cat)
                        },
                        text = { Text(label, fontSize = 13.sp) }
                    )
                }
            }

            if (searchQuery.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp), // Removed MaxHeight(0.7) to allow more space
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(searchResults) { item ->
                        ContentCard(item) {
                            scope.launch {
                                isSearching = true
                                if (item.provider == "consumet" || item.provider == "anilist") {
                                    val eps = catalog.getAnimeEpisodes(item.providerId)
                                    isSearching = false
                                    selectedSeries = item.copy(seasons = listOf(Season("Episodios", eps)))
                                } else {
                                    val resolvedUrl = catalog.resolveStream(item)
                                    isSearching = false
                                    if (resolvedUrl != null) {
                                        onSelect(resolvedUrl, item.title)
                                    } else {
                                        android.widget.Toast.makeText(context, "No se encontró enlace reproducible. Prueba con otro título.", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selectedSeries == null) {
                // v14.0: Enhanced Visual Catalog
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Header text mejorado
                        Text(
                            text = if (searchQuery.isEmpty()) "🎭 Explora el Catálogo" else "Resultados de Búsqueda",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = Color.White
                        )
                    }
                    
                    // v14.0: Filtrar contenido de tendencias según la categoría seleccionada
                    val filteredContent = when (selectedCategory) {
                        "anime" -> trendingContent.filter { it.key.contains("Anime", ignoreCase = true) }
                        "movie" -> trendingContent.filter { it.key.contains("Películas", ignoreCase = true) || it.key.contains("Cine", ignoreCase = true) }
                        "tv" -> trendingContent.filter { it.key.contains("Series", ignoreCase = true) }
                        "drama" -> trendingContent.filter { it.key.contains("Dramas", ignoreCase = true) || it.key.contains("K-Dramas", ignoreCase = true) }
                        else -> trendingContent // "all" muestra todo
                    }

                    filteredContent.forEach { (category, items) ->
                        if (items.isNotEmpty()) {
                            item {
                                Text(
                                    text = category,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
                                    color = Color.White
                                )
                            }
                            
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(items) { item ->
                                        ContentCard(item) {
                                            if (item.seasons.isNotEmpty()) {
                                                selectedSeries = item
                                            } else if (item.videoUrl != null) {
                                                onSelect(item.videoUrl, item.title)
                                            } else {
                                                // v7.8: Consumet support for trending/catalog
                                                scope.launch {
                                                    isSearching = true
                                                    if (item.provider == "consumet") {
                                                        val eps = catalog.getAnimeEpisodes(item.providerId)
                                                        isSearching = false
                                                        selectedSeries = item.copy(seasons = listOf(Season("Episodios", eps)))
                                                    } else {
                                                        val resolvedUrl = catalog.resolveStream(item)
                                                        isSearching = false
                                                        if (resolvedUrl != null) {
                                                            onSelect(resolvedUrl, item.title)
                                                        } else {
                                                            android.widget.Toast.makeText(context, "Enlace no disponible", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            } else if (selectedSeason == null) {
                // Season Selector
                Text(
                    text = "Selecciona Temporada: ${selectedSeries?.title}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )
                
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(selectedSeries?.seasons ?: emptyList()) { season ->
                        ListItem(
                            headlineContent = { Text(season.name, color = Color.White) },
                            supportingContent = { Text("${season.episodes.size} Episodios", color = Color.Gray) },
                            modifier = Modifier.clickable { selectedSeason = season },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    item {
                        TextButton(onClick = { selectedSeries = null }, modifier = Modifier.padding(16.dp)) {
                            Text("< Volver al catálogo")
                        }
                    }
                }
            } else {
                // Episode Selector
                Text(
                    text = "${selectedSeries?.title} - ${selectedSeason?.name}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )
                
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(selectedSeason?.episodes ?: emptyList()) { ep ->
                        ListItem(
                            headlineContent = { Text(ep.title, color = Color.White) },
                            modifier = Modifier.clickable { 
                                scope.launch {
                                    if (selectedSeries?.provider == "consumet") {
                                        // v7.8: Resolve the actual playback link for the episode
                                        isSearching = true
                                        val videoLink = catalog.resolveAnimeLink(ep.videoUrl)
                                        isSearching = false
                                        if (videoLink != null) {
                                            onSelect(videoLink, "${selectedSeries?.title} - ${ep.title}")
                                        } else {
                                            android.widget.Toast.makeText(context, "No se pudo obtener el enlace del episodio.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        onSelect(ep.videoUrl, "${selectedSeries?.title} - E${ep.number}: ${ep.title}")
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    item {
                        TextButton(onClick = { selectedSeason = null }, modifier = Modifier.padding(16.dp)) {
                            Text("< Volver a temporadas")
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
            .clickable { onClick() }
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
            
            // v7.2.4: Metadata Badge
            if (item.videoUrl == null && item.seasons.isEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = CircleShape
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp).size(14.dp),
                        tint = Color.Gray
                    )
                }
            } else if (item.provider == "tmdb" || item.provider == "tmdb_tv" || item.provider == "drama") {
                // v9.5: Real-Debrid Premium Badge (GOLD)
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    color = Color(0xFFFFD700).copy(alpha = 0.9f), 
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "PREMIUM 4K", 
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.Black
                    )
                }
            } else if (item.provider == "consumet") {
                // v7.8: Anime Playable Badge
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    color = Color(0xFFFF4081).copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "ANIME", 
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else if (item.videoUrl != null && !item.posterUrl.contains("tmdb") && !item.posterUrl.contains("anilist")) {
                // v7.4: Public Domain Playable Badge
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "GRATIS", 
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.videoUrl == null && item.seasons.isEmpty()) Color.Gray else Color.White,
            maxLines = 1,
            fontWeight = FontWeight.Medium
        )
    }
}

data class ContentItem(
    val title: String,
    val posterUrl: String,
    val videoUrl: String? = null,
    val seasons: List<Season> = emptyList(),
    val description: String = "",
    val provider: String = "manual", // tmdb, anilist, archive, manual
    val providerId: String = ""
)

data class Season(
    val name: String,
    val episodes: List<Episode>
)

data class Episode(
    val number: Int,
    val title: String,
    val videoUrl: String
)

private fun getContentCatalog(): Map<String, List<ContentItem>> {
    return mapOf(
        "Anime Real ⚡" to listOf(
            ContentItem(
                "Shingeki no Kyojin", 
                "https://images.alphacoders.com/279/279148.jpg",
                seasons = listOf(
                    Season("Temporada 1", listOf(
                        Episode(1, "A ti, dentro de 2000 años", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"),
                        Episode(2, "Ese día", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"),
                        Episode(3, "Una luz tenue", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4")
                    )),
                    Season("Temporada 2", listOf(
                         Episode(1, "Titán Bestia", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4")
                    ))
                )
            ),
            ContentItem(
                "Naruto Shippuden", 
                "https://images2.alphacoders.com/264/264177.jpg",
                seasons = listOf(
                    Season("Temporada 1", listOf(
                        Episode(1, "Regreso a Casa", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
                        Episode(2, "Los Akatsuki entran en acción", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
                    ))
                )
            ),
            ContentItem(
                "One Piece", 
                "https://wallpaperaccess.com/full/17350.jpg",
                seasons = listOf(
                    Season("East Blue", listOf(
                        Episode(1, "¡Soy Luffy!", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4")
                    ))
                )
            )
        ),
        "Películas de Estreno 🎬" to listOf(
             ContentItem("Big Buck Bunny", "https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
             ContentItem("Elephant Dream", "https://upload.wikimedia.org/wikipedia/commons/e/e8/Elephants_Dream_s7_vignette.jpg", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"),
             ContentItem("Tears of Steel", "https://mango.blender.org/wp-content/uploads/2012/05/p49_tos_vignette.jpg", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"),
             ContentItem("For Bigger Blazes", "https://storage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerBlazes.jpg", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"),
             ContentItem("For Bigger Joyrides", "https://storage.googleapis.com/gtv-videos-bucket/sample/images/ForBiggerJoyrides.jpg", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4")
        ),
        "Series Mundiales 📺" to listOf(
             ContentItem(
                 "The Steel Chronicles", 
                 "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/TearsOfSteel.jpg",
                 seasons = listOf(
                     Season("Temporada 1", listOf(
                         Episode(1, "Acero y Lágrimas", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"),
                         Episode(2, "Renacimiento", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4")
                     ))
                 )
             )
        ),
        "Novelas Clásicas 🌹" to listOf(
             ContentItem(
                 "Pasión de Gavilanes", 
                 "https://m.media-amazon.com/images/I/71YV1jXWqPL._AC_UF894,1000_QL80_.jpg",
                 seasons = listOf(
                     Season("Temporada 1", listOf(
                         Episode(1, "El Reencuentro", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
                         Episode(2, "Secretos de Familia", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
                     ))
                 )
             ),
             ContentItem(
                 "Betty la Fea", 
                 "https://m.media-amazon.com/images/I/51-mYj6KCRL._AC_UF1000,1000_QL80_.jpg",
                 seasons = listOf(
                     Season("Temporada 1", listOf(
                         Episode(1, "Un día en Ecomoda", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"),
                         Episode(2, "La propuesta", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4")
                     ))
                 )
             )
        ),
        "Documentales 🌍" to listOf(
             ContentItem("Planeta Blender", "https://upload.wikimedia.org/wikipedia/commons/0/08/Sintel_Poster.jpg", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4")
        )
    )
}
