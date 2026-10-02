package conectate.com.presentation.documents

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.documents.AvailableDocument
import conectate.com.data.documents.AvailableDocumentFolder
import conectate.com.data.documents.DocumentCategory
import conectate.com.data.documents.filterDocuments
import conectate.com.data.documents.formatDocumentSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The system picker is used only to grant a folder; choosing documents stays inside Novvin. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentPickerSheet(
    onDismiss: () -> Unit,
    onPicked: (Uri) -> Unit,
    viewModel: DocumentPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(DocumentCategory.ALL) }
    var folderUri by rememberSaveable { mutableStateOf<String?>(null) }
    var removingFolder by remember { mutableStateOf<AvailableDocumentFolder?>(null) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    var addingDocuments by rememberSaveable { mutableStateOf(false) }
    val folder = state.catalog.folders.firstOrNull { it.uri == folderUri }
    val activeFolderUri = folder?.uri
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::addFolder)
    }
    val addDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addDocuments(uris)
    }
    val documents by produceState<List<AvailableDocument>>(emptyList(), state.catalog.documents, query, category, activeFolderUri) {
        value = withContext(Dispatchers.Default) { filterDocuments(state.catalog.documents, category, query, activeFolderUri) }
    }
    val childFolders = remember(state.catalog.folders, activeFolderUri) {
        state.catalog.folders.filter { it.parentUri == activeFolderUri }.sortedBy { it.name.lowercase() }
    }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    DisposableEffect(viewModel) { onDispose { viewModel.stopLoading() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (folder != null) folderUri = folder.parentUri else onDismiss()
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (folder != null) "Carpeta anterior" else "Cerrar documentos") }
                Text("Documentos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { addingDocuments = true }, enabled = !state.loading) {
                    Icon(Icons.Default.Add, "Agregar documentos")
                }
                IconButton(onClick = viewModel::refresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, "Actualizar documentos") }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cerrar selector") }
            }
            Text("Selecciona un documento para verlo o enviarlo.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(120) }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                singleLine = true, label = { Text("Buscar nombre o carpeta") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Limpiar búsqueda") } },
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DocumentCategory.entries) { type ->
                    FilterChip(selected = category == type, onClick = { category = type }, label = { Text(type.label) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(folder?.path ?: "Todas las carpetas", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { addingDocuments = true }, enabled = !state.loading) {
                    Text("Agregar")
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            (permissionError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.catalog.limited) Text("Mostramos hasta 2500 archivos. Agrega una carpeta más específica si falta alguno.", style = MaterialTheme.typography.bodySmall)
            if (state.catalog.unavailableFolders > 0) Text("Algunos archivos o carpetas no están disponibles. Revisa su permiso o conexión.", style = MaterialTheme.typography.bodySmall)
            Text("${documents.size} archivos · incluye subcarpetas", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 6.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (query.isBlank() && childFolders.isNotEmpty()) {
                    items(childFolders, key = { "folder:${it.uri}" }) { child ->
                        ListItem(
                            headlineContent = { Text(child.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("Abrir carpeta") },
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            trailingContent = {
                                if (child.grantUri != null) IconButton(onClick = { removingFolder = child }) {
                                    Icon(Icons.Default.FolderOff, "Quitar acceso a ${child.name}")
                                } else Icon(Icons.Default.ChevronRight, null)
                            },
                            modifier = Modifier.clickable { folderUri = child.uri },
                        )
                    }
                }
                if (documents.isEmpty() && !state.loading) item {
                    Text(
                        if (state.catalog.folders.isEmpty()) "Tus documentos aparecerán aquí. Agrega una carpeta una vez para explorar sus archivos dentro de Novvin."
                        else "No hay documentos que coincidan. Prueba otra categoría o cambia la búsqueda.",
                        modifier = Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(documents, key = { "document:${it.uri}" }) { document ->
                    ListItem(
                        headlineContent = { Text(document.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text("${document.category.label} · ${formatDocumentSize(document.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                                Text(document.folderPath, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        leadingContent = { Icon(when (document.category) {
                            DocumentCategory.PDF -> Icons.Default.PictureAsPdf
                            DocumentCategory.EXCEL -> Icons.Default.TableChart
                            DocumentCategory.POWERPOINT -> Icons.Default.Slideshow
                            DocumentCategory.WORD, DocumentCategory.TEXT -> Icons.Default.Description
                            else -> Icons.AutoMirrored.Filled.InsertDriveFile
                        }, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onPicked(Uri.parse(document.uri)) },
                    )
                }
            }
        }
    }
    if (addingDocuments) {
        AlertDialog(
            onDismissRequest = { addingDocuments = false },
            title = { Text("Tus documentos en Novvin") },
            text = { Text("Autoriza una carpeta una vez y luego busca, abre y selecciona sus documentos aquí. Android te pedirá elegir la carpeta para dar permiso. También puedes agregar archivos sueltos o de la nube.") },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        addingDocuments = false
                        permissionError = null
                        runCatching { addFolder.launch(null) }
                            .onFailure { permissionError = "No se pudo pedir acceso a la carpeta. Intenta agregar archivos." }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Agregar carpeta")
                    }
                    OutlinedButton(onClick = {
                        addingDocuments = false
                        permissionError = null
                        runCatching { addDocuments.launch(arrayOf("*/*")) }
                            .onFailure { permissionError = "No se pudo pedir acceso a los documentos. Inténtalo de nuevo." }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Agregar archivos") }
                    TextButton(onClick = { addingDocuments = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
                }
            },
        )
    }
    removingFolder?.let { removing ->
        AlertDialog(onDismissRequest = { removingFolder = null }, title = { Text("Quitar acceso a esta carpeta") },
            text = { Text("Sus documentos dejarán de aparecer aquí. Los archivos originales se conservan.") },
            confirmButton = { TextButton(onClick = {
                removing.grantUri?.let { viewModel.removeFolder(Uri.parse(it)) }
                if (folderUri == removing.uri) folderUri = null
                removingFolder = null
            }) { Text("Quitar acceso") } },
            dismissButton = { TextButton(onClick = { removingFolder = null }) { Text("Cancelar") } })
    }
}
