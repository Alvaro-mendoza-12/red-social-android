package conectate.com.presentation.search

import androidx.compose.runtime.Composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import conectate.com.data.model.User
import conectate.com.data.model.SemanticSearchResult
import conectate.com.presentation.navigation.Screen
import conectate.com.presentation.components.UserItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchUsersScreen(
    navController: NavController,
    viewModel: SearchUsersViewModel = hiltViewModel()
) {
    val searchQuery by viewModel.query.collectAsState()
    val accountId by viewModel.accountId.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val contentResults by viewModel.contentResults.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val userErrorMessage by viewModel.userErrorMessage.collectAsState()
    val history by viewModel.history.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val hashtags by viewModel.hashtags.collectAsState()
    val loadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMore by viewModel.hasMoreContent.collectAsState()
    var selectedTab by rememberSaveable(accountId) { mutableIntStateOf(viewModel.initialCategory) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit(query: String) {
        viewModel.submitSearch(query)
        keyboard?.hide()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Buscar en Novvin") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ListItem(
                headlineContent = { Text("Personas cerca") },
                supportingContent = { Text("Radar y chat aleatorio") },
                leadingContent = { Icon(Icons.Default.MyLocation, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { navController.navigate(Screen.Proximity.route) }
            )
            HorizontalDivider()

            PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
                listOf("Personas", "Contenido", "Videos", "Etiquetas").forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::searchUsers,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = {
                    Text(if (selectedTab == 0) "Nombre o @usuario" else "Temas, videos o #etiquetas")
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { 
                            viewModel.clearSearch()
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(searchQuery) }),
            )

            if (searchQuery.isBlank()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
                    if (history.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tus búsquedas recientes", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = viewModel::clearHistory) { Text("Borrar todo") }
                        }
                    }
                    items(history, key = { searchTermKey(it) }) { query ->
                        ListItem(
                            headlineContent = { Text(query, maxLines = 2) },
                            leadingContent = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = { viewModel.removeHistory(query) }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Eliminar búsqueda $query")
                                }
                            },
                            modifier = Modifier.clickable { submit(query) },
                        )
                    }
                    item { Text("Busca personas, temas y contenido de Novvin.", Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                return@Column
            }

            if (suggestions.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions, key = { searchTermKey(it.query) }) { suggestion ->
                        SuggestionChip(onClick = { submit(suggestion.query); selectedTab = 1 },
                            label = { Text(suggestion.query, maxLines = 1) },
                            icon = { Icon(Icons.Default.Search, contentDescription = suggestion.label) })
                    }
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            (if (selectedTab == 0) userErrorMessage else errorMessage)?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (!isLoading && searchResults.isEmpty() && selectedTab == 0 && userErrorMessage == null) {
                Text(
                    if (searchQuery.trim().removePrefix("@").trim().length < 2)
                        "Escribe al menos dos caracteres del nombre o @usuario."
                    else "No encontramos personas. Prueba con otro nombre o @usuario.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (selectedTab == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(searchResults, key = { it.id }) { user ->
                        UserItem(
                            user = user,
                            handleFirst = true,
                            onClick = {
                                navController.navigate(Screen.UserProfile.createRoute(user.id))
                            }
                        )
                        HorizontalDivider()
                    }
                }
            } else if (selectedTab == 3) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    items(hashtags, key = { searchTermKey(it.tag) }) { hashtag ->
                        ListItem(
                            headlineContent = { Text("#${hashtag.tag}") },
                            supportingContent = { Text("${hashtag.resultCount} ${if (hashtag.resultCount == 1) "resultado cargado" else "resultados cargados"}") },
                            modifier = Modifier.clickable { submit("#${hashtag.tag}"); selectedTab = 1 },
                        )
                    }
                    if (hashtags.isEmpty() && !isLoading) item {
                        Text("No hay etiquetas en los resultados cargados. Prueba otra consulta.")
                    }
                    if (hasMore) item { TextButton(onClick = viewModel::loadMore, enabled = !loadingMore) {
                        Text(if (loadingMore) "Cargando…" else "Cargar más resultados")
                    } }
                }
            } else {
                val visibleContent = if (selectedTab == 2) contentResults.filter { it.type == "video" } else contentResults
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleContent, key = { "${it.type}:${it.id}" }) { result ->
                        SemanticContentResultCard(
                            result = result,
                            onClick = {
                                val route = if (result.type == "video") {
                                    Screen.VideoPlayer.createRoute(result.id)
                                } else {
                                    Screen.PostDetail.createRoute(result.id)
                                }
                                navController.navigate(route)
                            }
                        )
                    }
                    if (visibleContent.isEmpty() && !isLoading && errorMessage == null) item {
                        Text(if (selectedTab == 2) "No encontramos videos para esta consulta." else "No encontramos contenido. Prueba un tema relacionado.")
                    }
                    if (hasMore) item {
                        TextButton(onClick = viewModel::loadMore, enabled = !loadingMore, modifier = Modifier.fillMaxWidth()) {
                            Text(if (loadingMore) "Cargando…" else if (errorMessage != null) "Reintentar más resultados" else "Cargar más resultados")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SemanticContentResultCard(
    result: SemanticSearchResult,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (result.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(result.thumbnailUrl)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(74.dp)
                        .clip(MaterialTheme.shapes.medium)
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(74.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (result.type == "video") Icons.Default.PlayCircle else Icons.Default.Public,
                            contentDescription = null
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title.ifBlank { if (result.type == "video") "Video" else "Publicación" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2
                )
                if (result.excerpt.isNotBlank() && result.excerpt != result.title) {
                    Text(
                        text = result.excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Text(
                    text = "${if (result.type == "video") "Video" else "Publicación"} · ${result.authorName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (result.reason == "semantic_concept") "Tema relacionado" else "Coincidencia por significado",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Abrir")
        }
    }
}

