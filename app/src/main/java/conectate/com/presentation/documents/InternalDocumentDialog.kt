package conectate.com.presentation.documents

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import conectate.com.presentation.common.AppFeedbackBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** Shared by private, group and community attachment previews and received file bubbles. */
@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun InternalDocumentDialog(
    source: Uri,
    onDismiss: () -> Unit,
    suggestedName: String? = null,
    onSend: ((Uri) -> Unit)? = null,
    resolveSource: (suspend (String) -> String?)? = null,
) {
    val context = LocalContext.current
    val session = remember(context, source) { DocumentReadSession(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var document by remember(source) { mutableStateOf<OpenedDocument?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var busy by remember(source) { mutableStateOf(false) }
    var draft by remember(source) { mutableStateOf("") }
    var editing by remember(source) { mutableStateOf(false) }
    var pendingCopy by remember(source) { mutableStateOf<File?>(null) }
    var pendingSource by remember(source) { mutableStateOf<Uri?>(null) }
    var resolvedSource by remember(source) { mutableStateOf<Uri?>(null) }
    var pendingPdfSend by remember(source) { mutableStateOf<File?>(null) }
    var offerSendAfterSave by remember(source) { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var wordEdits by remember(source) { mutableStateOf<List<WordParagraphEdit>>(emptyList()) }
    val openedOffice = document?.body as? DocumentBody.Office
    val wordOriginal = openedOffice?.value?.takeIf { it.format.startsWith("Word") }?.wordParagraphs.orEmpty()
    val wordChanged = wordOriginal.indices.any { index ->
        wordEdits.getOrNull(index)?.let { it.text != wordOriginal[index].text || it.bold != null || it.italic != null } == true
    }
    val changed = (document?.body as? DocumentBody.Text)?.value?.let { it != draft } == true || wordChanged
    val destinationName = pendingCopy?.name ?: document?.name ?: suggestedName
        ?: pendingSource?.lastPathSegment ?: source.lastPathSegment.orEmpty()
    val destinationMimeType = documentMimeType(
        destinationName,
        document?.mimeType ?: "application/octet-stream",
    )
    fun requestClose() { if (changed) confirmDiscard = true else onDismiss() }

    DisposableEffect(session) { onDispose { session.close() } }
    LaunchedEffect(source, session) {
        try {
            val resolved = if (resolveSource != null) {
                resolveSource(source.toString()) ?: error("El documento ya no está disponible o requiere renovar el acceso.")
            } else source.toString()
            resolvedSource = Uri.parse(resolved)
            val sourceName = suggestedName?.takeIf { it.contains('.') } ?: source.lastPathSegment
            val opened = session.open(Uri.parse(resolved), sourceName)
            document = opened
            draft = (opened.body as? DocumentBody.Text)?.value.orEmpty()
            wordEdits = ((opened.body as? DocumentBody.Office)?.value?.wordParagraphs ?: emptyList())
                .map { WordParagraphEdit(it.text) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "No se pudo leer el documento." }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(destinationMimeType)
    ) { destination ->
        val copy = pendingCopy
        val sourceToCopy = pendingSource
        if (destination != null && (copy != null || sourceToCopy != null)) scope.launch {
            busy = true
            try {
                if (copy != null) saveDocumentCopy(context, copy, destination, source)
                else copyDocumentSource(context, sourceToCopy!!, destination)
                AppFeedbackBus.showSuccess("Copia guardada; el original se conserva")
                if (offerSendAfterSave && onSend != null && copy != null) pendingPdfSend = copy
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "No se pudo guardar la copia." }
            finally { busy = false; offerSendAfterSave = false; pendingSource = null; pendingCopy = null }
        }
    }

    fun export(asPdf: Boolean, forSending: Boolean, textOnly: Boolean = false, asDocxCopy: Boolean = false) {
        val opened = document ?: return
        if (busy) return
        val text = when (val body = opened.body) {
            is DocumentBody.Text -> draft
            is DocumentBody.Office -> if (body.value.format.startsWith("Word") && wordEdits.size == body.value.wordParagraphs.size)
                wordEdits.joinToString("\n") { it.text } else body.value.plainText()
            else -> return
        }
        busy = true
        scope.launch {
            try {
                if (asDocxCopy) {
                    val office = opened.body as? DocumentBody.Office
                        ?: error("Solo se puede guardar una copia DOCX desde un documento Word.")
                    require(office.value.format.startsWith("Word") && wordChanged && opened.file != null) {
                        "Edita al menos un párrafo de Word antes de guardar la copia."
                    }
                    val copy = WordDocumentEditor.createEditedCopy(context, opened.file!!, opened.name, wordEdits)
                    if (forSending) onSend?.invoke(Uri.fromFile(copy))
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveDocumentToDownloads(context, copy, copy.name, documentMimeType(copy.name))
                        AppFeedbackBus.showSuccess("Copia DOCX guardada en Descargas/Novvin; el original se conserva")
                    } else {
                        pendingCopy = copy
                        saveLauncher.launch(copy.name)
                    }
                    return@launch
                }
                var temporaryEditedDocx: File? = null
                val richHtml = try {
                    val office = (opened.body as? DocumentBody.Office)?.value
                    if (office?.format?.startsWith("Word") == true && wordChanged && opened.file != null) {
                        temporaryEditedDocx = WordDocumentEditor.createEditedCopy(context, opened.file, opened.name, wordEdits)
                        temporaryEditedDocx!!.inputStream().use { DocumentContentParser.parseOffice(it).htmlPreview }
                    } else office?.htmlPreview
                } finally {
                    temporaryEditedDocx?.delete()
                }
                val copy = if (asPdf && !textOnly && richHtml != null) {
                    createRichDocumentPdf(context, opened.name, richHtml)
                } else createDocumentCopy(context, opened.name, text, asPdf)
                if (forSending) onSend?.invoke(Uri.fromFile(copy))
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveDocumentToDownloads(context, copy, copy.name, documentMimeType(copy.name))
                    AppFeedbackBus.showSuccess("Guardado en Descargas/Novvin")
                    if (asPdf && onSend != null) pendingPdfSend = copy
                } else {
                    pendingCopy = copy
                    offerSendAfterSave = asPdf && onSend != null
                    saveLauncher.launch(copy.name)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "No se pudo convertir el documento." }
            finally { busy = false }
        }
    }

    Dialog(onDismissRequest = ::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopAppBar(
                    title = { Text(document?.name ?: suggestedName ?: "Documento", maxLines = 2) },
                    navigationIcon = { IconButton(onClick = ::requestClose) { Icon(Icons.Default.Close, "Cerrar documento") } }
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                val opened = document
                if (opened == null && error == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (opened != null) {
                    Text("${opened.size / 1_024} KB · ${when (opened.body) {
                        is DocumentBody.Pdf -> "PDF · lectura"
                        is DocumentBody.Text -> if (editing) "Edición de texto · copia UTF-8" else "Texto"
                        is DocumentBody.Office -> opened.body.value.format
                        is DocumentBody.Unsupported -> "Archivo"
                    }}", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                    when (val body = opened.body) {
                        is DocumentBody.Pdf -> PdfDocumentPages(opened.file!!, body.pages, Modifier.weight(1f))
                        is DocumentBody.Text -> {
                            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Editar como texto", Modifier.weight(1f))
                                Switch(checked = editing, onCheckedChange = { editing = it })
                            }
                            if (editing) OutlinedTextField(
                                value = draft,
                                onValueChange = { if (it.length <= DocumentLimits.TEXT_CHARACTERS) draft = it },
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                label = { Text("Contenido (${draft.length}/200000)") },
                            ) else SelectionContainer(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
                                Text(
                                    draft.ifBlank { "Documento vacío" },
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.35f,
                                    ),
                                )
                            }
                        }
                        is DocumentBody.Office -> {
                            var visual by remember(opened.file) { mutableStateOf(body.value.htmlPreview != null) }
                            val canEditWord = body.value.format.startsWith("Word") && wordEdits.size == body.value.wordParagraphs.size
                            if (canEditWord) Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Editar Word como copia", Modifier.weight(1f))
                                Switch(checked = editing, onCheckedChange = { editing = it })
                            }
                            if (editing && canEditWord) {
                                Text(
                                    "El archivo original queda intacto. Se conservan los párrafos con enlaces, imágenes, campos, saltos especiales o varios segmentos de formato.",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                WordParagraphEditor(
                                    paragraphs = body.value.wordParagraphs,
                                    edits = wordEdits,
                                    onEdit = { index, edit -> wordEdits = wordEdits.toMutableList().also { it[index] = edit } },
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            } else {
                            Text("Vista interna del contenido disponible. No se ejecutan macros ni fórmulas; verifica el diseño antes de enviar.",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
                            if (body.value.htmlPreview != null) Text(
                                "La exportación PDF conserva el contenido visual compatible; el diseño puede variar respecto al archivo abierto en Office.",
                                modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (body.value.htmlPreview != null) {
                                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = visual, onClick = { visual = true }, label = { Text("Vista visual") })
                                    FilterChip(selected = !visual, onClick = { visual = false }, label = { Text("Texto") })
                                }
                            }
                            if (visual && body.value.htmlPreview != null) {
                                OfficeHtmlPreview(body.value.htmlPreview, Modifier.weight(1f).fillMaxWidth())
                            } else {
                                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    itemsIndexed(body.value.sections) { _, section ->
                                        ElevatedCard(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(section.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                                HorizontalDivider()
                                                SelectionContainer {
                                                    Text(
                                                        section.text.ifBlank { "Sin texto disponible" },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        style = MaterialTheme.typography.bodyLarge.copy(
                                                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.3f,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            }
                        }
                        is DocumentBody.Unsupported -> Text(body.reason, modifier = Modifier.weight(1f).padding(20.dp))
                    }
                    if (opened.body is DocumentBody.Text || opened.body is DocumentBody.Office) {
                        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { export(false, false) }, enabled = !busy) {
                                Text(if (opened.body is DocumentBody.Office) "Guardar texto" else "Guardar copia")
                            }
                            OutlinedButton(onClick = { export(true, false) }, enabled = !busy) {
                                Text(if (opened.body is DocumentBody.Office && opened.body.value.htmlPreview == null)
                                    "Guardar PDF de texto" else "Guardar PDF")
                            }
                            if (opened.body is DocumentBody.Office && opened.body.value.htmlPreview != null) {
                                TextButton(onClick = { export(true, false, textOnly = true) }, enabled = !busy) {
                                    Text("PDF de texto")
                                }
                            }
                            if (onSend != null) {
                                TextButton(onClick = { export(true, true) }, enabled = !busy) {
                                    Text(if (opened.body is DocumentBody.Office && opened.body.value.htmlPreview == null)
                                        "Enviar PDF de texto" else "Enviar como PDF")
                                }
                                if (wordChanged) TextButton(onClick = { export(false, true, asDocxCopy = true) }, enabled = !busy) {
                                    Text("Enviar copia DOCX")
                                } else if (changed) TextButton(onClick = { export(false, true) }, enabled = !busy) {
                                    Text("Enviar copia editada")
                                }
                            }
                            if (wordChanged) OutlinedButton(onClick = { export(false, false, asDocxCopy = true) }, enabled = !busy) {
                                Text("Guardar copia DOCX")
                            }
                        }
                    }
                } else Spacer(Modifier.weight(1f))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (onSend == null && resolvedSource != null) {
                    Button(onClick = {
                        if (busy) return@Button
                        busy = true
                        scope.launch {
                            try {
                                val name = opened?.name ?: suggestedName ?: "Documento"
                                val mime = opened?.mimeType ?: documentMimeType(name)
                                val file = opened?.file
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    if (file != null) saveDocumentToDownloads(context, file, name, mime)
                                    else saveDocumentSourceToDownloads(context, resolvedSource!!, name, mime)
                                    AppFeedbackBus.showSuccess("Documento guardado en Descargas/Novvin")
                                } else {
                                    pendingCopy = file
                                    pendingSource = if (file == null) resolvedSource else null
                                    saveLauncher.launch(name)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "No se pudo descargar el documento." }
                            finally { busy = false }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text("↓ Descargar documento")
                    }
                }
                if (onSend != null) Button(
                    onClick = { onSend(source) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) { Text(if (changed) "Enviar original" else "Enviar documento") }
            }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Hay cambios sin guardar") },
        text = { Text("El original no se modificó. Guarda una copia para conservar tus cambios.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar sin guardar") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Seguir editando") } }
    )
    pendingPdfSend?.let { pdf ->
        AlertDialog(
            onDismissRequest = { pendingPdfSend = null },
            title = { Text("PDF guardado") },
            text = { Text("¿Deseas enviar también esta copia en PDF a la conversación?") },
            confirmButton = { TextButton(onClick = { pendingPdfSend = null; onSend?.invoke(Uri.fromFile(pdf)) }) { Text("Enviar PDF") } },
            dismissButton = { TextButton(onClick = { pendingPdfSend = null }) { Text("Solo guardar") } },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun WordParagraphEditor(
    paragraphs: List<WordParagraph>,
    edits: List<WordParagraphEdit>,
    onEdit: (Int, WordParagraphEdit) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(paragraphs) { index, paragraph ->
            val edit = edits.getOrNull(index) ?: WordParagraphEdit(paragraph.text)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Párrafo ${index + 1}", style = MaterialTheme.typography.labelLarge)
                    if (!paragraph.editable) {
                        Text(
                            "Solo lectura: contiene un elemento Word avanzado.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(paragraph.text.ifBlank { "(vacío)" }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
                        }
                    } else {
                        // Keep formatting controls above the editor so opening the keyboard does not cover them.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val boldSelected = edit.bold ?: (paragraph.bold == true)
                            FilterChip(
                                selected = boldSelected,
                                onClick = {
                                    val next = !boldSelected
                                    val override = paragraph.bold?.let { if (it == next) null else next } ?: next
                                    onEdit(index, edit.copy(bold = override))
                                },
                                label = { Text("Negrita") },
                            )
                            val italicSelected = edit.italic ?: (paragraph.italic == true)
                            FilterChip(
                                selected = italicSelected,
                                onClick = {
                                    val next = !italicSelected
                                    val override = paragraph.italic?.let { if (it == next) null else next } ?: next
                                    onEdit(index, edit.copy(italic = override))
                                },
                                label = { Text("Cursiva") },
                            )
                        }
                        if (paragraph.textEditable) {
                            OutlinedTextField(
                                value = edit.text,
                                onValueChange = { value ->
                                    val total = edits.sumOf { it.text.length } - edit.text.length + value.length
                                    if (value.length <= DocumentLimits.TEXT_CHARACTERS && total <= DocumentLimits.TEXT_CHARACTERS &&
                                        '\n' !in value && '\r' !in value) onEdit(index, edit.copy(text = value))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Texto") },
                                singleLine = true,
                            )
                        } else {
                            Text(
                                "Texto conservado con su formato original",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(paragraph.text.ifBlank { "(vacío)" }, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfficeHtmlPreview(html: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val webView = remember(context, html) {
        WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            }
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }
    AndroidView(factory = { webView }, modifier = modifier)
}

@Composable
private fun PdfDocumentPages(file: File, pages: Int, modifier: Modifier = Modifier) {
    var page by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(file) { mutableStateOf<String?>(null) }
    var zoom by remember(file, page) { mutableFloatStateOf(1f) }
    var pan by remember(file, page) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val zoomState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
        val maxX = (nextZoom - 1f) * viewport.width / 2f
        val maxY = (nextZoom - 1f) * viewport.height / 2f
        val scaleShift = 1f - nextZoom / zoom
        val centered = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        pan = Offset((pan.x + panChange.x + centered.x * scaleShift).coerceIn(-maxX, maxX),
            (pan.y + panChange.y + centered.y * scaleShift).coerceIn(-maxY, maxY))
        zoom = nextZoom
    }
    LaunchedEffect(file, page) {
        failure = null
        bitmap = null
        try { bitmap = renderDocumentPdfPage(file, page) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "No se pudo dibujar esta página." }
    }
    // Render only the current page, bounded to 1200 × 1600 pixels; old bitmaps are reclaimed by Android.
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text("Anterior") }
            Text("${page + 1} / $pages")
            TextButton(onClick = { page++ }, enabled = page + 1 < pages) { Text("Siguiente") }
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }, enabled = zoom > 1f) {
                Text("${(zoom * 100).toInt()} %")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp).onSizeChanged { viewport = it }
            .clipToBounds().transformable(zoomState),
            contentAlignment = Alignment.Center) {
            val rendered = bitmap
            when {
                rendered != null -> Image(rendered.asImageBitmap(), "Página ${page + 1}",
                    Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom,
                        translationX = pan.x, translationY = pan.y))
                failure != null -> Text(failure!!)
                else -> CircularProgressIndicator()
            }
        }
    }
}
