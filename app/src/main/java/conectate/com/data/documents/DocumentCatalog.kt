package conectate.com.data.documents

import java.text.Normalizer
import java.util.Locale

enum class DocumentCategory(val label: String) {
    ALL("Todos"), PDF("PDF"), WORD("Word"), EXCEL("Excel"), POWERPOINT("PowerPoint"), TEXT("Texto"), OTHER("Otros")
}

data class AvailableDocument(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAt: Long?,
    val folderPath: String,
    val ancestorUris: List<String>,
    val category: DocumentCategory = documentCategory(name, mimeType),
)

data class AvailableDocumentFolder(
    val uri: String,
    val name: String,
    val path: String,
    val parentUri: String?,
    val grantUri: String? = null,
)

data class DocumentCatalog(
    val documents: List<AvailableDocument> = emptyList(),
    val folders: List<AvailableDocumentFolder> = emptyList(),
    val limited: Boolean = false,
    val unavailableFolders: Int = 0,
)

fun documentCategory(name: String, mimeType: String): DocumentCategory {
    val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        mime == "application/pdf" || extension == "pdf" -> DocumentCategory.PDF
        mime in setOf("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.oasis.opendocument.text") ||
            extension in setOf("doc", "docx", "odt", "rtf", "docm") -> DocumentCategory.WORD
        mime in setOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.oasis.opendocument.spreadsheet", "text/csv") ||
            extension in setOf("xls", "xlsx", "xlsm", "xlsb", "ods", "csv", "tsv") -> DocumentCategory.EXCEL
        mime in setOf("application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.oasis.opendocument.presentation") ||
            extension in setOf("ppt", "pptx", "pptm", "pps", "ppsx", "odp") -> DocumentCategory.POWERPOINT
        mime.startsWith("text/") || mime in setOf("application/json", "application/xml") ||
            extension in setOf("txt", "md", "markdown", "log", "json", "xml", "yaml", "yml", "html", "htm") -> DocumentCategory.TEXT
        else -> DocumentCategory.OTHER
    }
}

private fun searchKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

fun filterDocuments(
    documents: List<AvailableDocument>,
    category: DocumentCategory,
    query: String,
    folderUri: String?,
    allowedExtensions: Set<String>? = null,
): List<AvailableDocument> {
    val extensions = allowedExtensions?.map { it.trim().removePrefix(".").lowercase(Locale.ROOT) }?.toSet()
    val words = searchKey(query.trim()).split(Regex("\\s+")).filter(String::isNotEmpty)
    return documents.asSequence()
        .filter { extensions == null || it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions }
        .filter { category == DocumentCategory.ALL || it.category == category }
        .filter { folderUri == null || folderUri in it.ancestorUris }
        .filter { document ->
            val searchable = searchKey("${document.name} ${document.folderPath}")
            words.all(searchable::contains)
        }
        .distinctBy { it.uri }
        .sortedWith(compareBy<AvailableDocument> { searchKey(it.name) }.thenBy { it.uri })
        .toList()
}

fun formatDocumentSize(sizeBytes: Long?): String = when {
    sizeBytes == null || sizeBytes < 0 -> "Tamaño no disponible"
    sizeBytes < 1024 -> "$sizeBytes B"
    sizeBytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", sizeBytes / 1024.0)
    sizeBytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", sizeBytes / (1024.0 * 1024))
    else -> String.format(Locale.getDefault(), "%.1f GB", sizeBytes / (1024.0 * 1024 * 1024))
}
