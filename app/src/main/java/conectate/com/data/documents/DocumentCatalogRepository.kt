package conectate.com.data.documents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Reads only trees explicitly granted to this app; no all-files permission or raw disk traversal. */
@Singleton
class DocumentCatalogRepository @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val resolver get() = context.contentResolver
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

    /**
     * Remembers one file selected with the in-app picker.  OpenDocument grants a
     * read URI permission for this exact file, so the catalog does not need the
     * dangerous all-files permission and still works when the user has no folder
     * grant configured.
     */
    suspend fun addDocument(uri: Uri) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Selecciona un documento del dispositivo." }
        // Do not list a document that will disappear after process death because
        // its provider did not grant persistent access. The ViewModel surfaces
        // SecurityException with an actionable permission message.
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val current = preferences.getStringSet(INDIVIDUAL_DOCUMENTS_KEY, emptySet()).orEmpty().toMutableSet()
        require(current.size < MAX_INDIVIDUAL_DOCUMENTS || uri.toString() in current) {
            "Puedes guardar hasta $MAX_INDIVIDUAL_DOCUMENTS documentos elegidos."
        }
        current += uri.toString()
        preferences.edit().putStringSet(INDIVIDUAL_DOCUMENTS_KEY, current).apply()
    }

    suspend fun grantTree(uri: Uri) = withContext(Dispatchers.IO) {
        require(DocumentsContract.isTreeUri(uri)) { "Selecciona una carpeta accesible." }
        val existing = resolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        require(existing.any { it.uri == uri } || existing.size < MAX_ROOTS) { "Puedes agregar hasta $MAX_ROOTS carpetas. Quita una antes de añadir otra." }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun forgetTree(uri: Uri) = withContext(Dispatchers.IO) {
        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun scan(): DocumentCatalog = withTimeout(15_000L) { coroutineScope {
        val cancellation = CancellationSignal()
        // Binder queries need their own cancellation signal; cancelling only an IO coroutine is insufficient.
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { cancellation.cancel() }
        }
        try {
            withContext(Dispatchers.IO) { scanGrantedTrees(cancellation) }
        } finally { cancellationWatcher.cancel() }
    } }

    private data class ScanFolder(val tree: Uri, val folder: AvailableDocumentFolder, val documentId: String, val ancestors: List<String>, val depth: Int)

    private suspend fun scanGrantedTrees(signal: CancellationSignal): DocumentCatalog {
        val granted = resolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .distinctBy { it.uri.toString() }
        val documents = mutableListOf<AvailableDocument>()
        val folders = mutableListOf<AvailableDocumentFolder>()
        val queue = ArrayDeque<ScanFolder>()
        val visited = mutableSetOf<String>()
        var unavailable = 0
        var limited = granted.size > MAX_ROOTS
        val deadline = SystemClock.elapsedRealtime() + SCAN_BUDGET_MS
        for (grant in granted.take(MAX_ROOTS)) {
            currentCoroutineContext().ensureActive()
            try {
                val treeId = DocumentsContract.getTreeDocumentId(grant.uri)
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(grant.uri, treeId)
                val rootName = resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null, signal)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.takeIf(String::isNotBlank) ?: treeId.substringAfterLast(':').ifBlank { "Carpeta" }
                val root = AvailableDocumentFolder(rootUri.toString(), rootName, rootName, null, grant.uri.toString())
                folders += root
                queue += ScanFolder(grant.uri, root, treeId, listOf(root.uri), 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                unavailable++
            }
        }
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (documents.size >= MAX_DOCUMENTS || visited.size >= MAX_FOLDERS || SystemClock.elapsedRealtime() >= deadline) {
                limited = true
                break
            }
            val current = queue.removeFirst()
            if (!visited.add("${current.tree.authority}:${current.documentId}")) continue
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(current.tree, current.documentId)
                resolver.query(children, PROJECTION, null, null, null, signal)?.use { cursor ->
                    var childCount = 0
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        if (++childCount > MAX_CHILDREN || documents.size >= MAX_DOCUMENTS || SystemClock.elapsedRealtime() >= deadline) {
                            limited = true
                            break
                        }
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1)?.takeIf(String::isNotBlank) ?: "Sin nombre"
                        val mime = cursor.getString(2) ?: "application/octet-stream"
                        val uri = DocumentsContract.buildDocumentUriUsingTree(current.tree, id).toString()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (current.depth >= MAX_DEPTH || folders.size >= MAX_FOLDERS) {
                                limited = true
                                continue
                            }
                            val folder = AvailableDocumentFolder(uri, name, "${current.folder.path}/$name", current.folder.uri)
                            folders += folder
                            queue += ScanFolder(current.tree, folder, id, current.ancestors + uri, current.depth + 1)
                        } else {
                            val flags = if (cursor.isNull(5)) 0 else cursor.getInt(5)
                            // Virtual documents need a conversion stream; the ordinary message sender cannot read them.
                            if (flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) continue
                            documents += AvailableDocument(
                                uri, name, mime,
                                if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it >= 0 },
                                if (cursor.isNull(4)) null else cursor.getLong(4).takeIf { it > 0 },
                                current.folder.path, current.ancestors,
                            )
                        }
                    }
                } ?: run { unavailable++ }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                unavailable++
            }
        }
        // A single-file picker is the reliable fallback for providers that do
        // not expose a browsable tree (Downloads, cloud drives and OEM files).
        // Include those entries alongside folder results so the user always has
        // a visible, organized document list after selecting a file.
        for (savedUri in preferences.getStringSet(INDIVIDUAL_DOCUMENTS_KEY, emptySet()).orEmpty()) {
            currentCoroutineContext().ensureActive()
            val uri = Uri.parse(savedUri)
            try {
                val document = readIndividualDocument(uri, signal)
                if (document != null) documents += document
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                unavailable++
            }
        }
        return DocumentCatalog(documents.distinctBy { it.uri }, folders.distinctBy { it.uri }, limited, unavailable)
    }

    private fun readIndividualDocument(uri: Uri, signal: CancellationSignal): AvailableDocument? {
        if (uri.scheme != "content") return null
        var name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "Documento"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let { name = cursor.getString(it).takeIf(String::isNotBlank) ?: name }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let { size = cursor.getLong(it).takeIf { value -> value >= 0L } }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return AvailableDocument(
            uri = uri.toString(), name = name, mimeType = mime, sizeBytes = size,
            modifiedAt = null, folderPath = "Documentos elegidos", ancestorUris = emptyList(),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "document_catalog"
        const val INDIVIDUAL_DOCUMENTS_KEY = "individual_documents"
        const val MAX_INDIVIDUAL_DOCUMENTS = 100
        const val MAX_ROOTS = 12
        const val MAX_DOCUMENTS = 2500
        const val MAX_FOLDERS = 180
        const val MAX_DEPTH = 8
        const val MAX_CHILDREN = 3000
        const val SCAN_BUDGET_MS = 12_000L
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
