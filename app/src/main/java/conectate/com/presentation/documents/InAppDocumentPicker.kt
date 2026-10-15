package conectate.com.presentation.documents

import android.net.Uri
import androidx.compose.runtime.*
import conectate.com.presentation.components.DeviceAudioPickerDialog

/** Uses the authorized document catalog; external UI is limited to explicit access grants. */
class InAppDocumentPickerLauncher internal constructor(private val open: (Array<String>) -> Unit) {
    fun launch(mimeTypes: Array<String>) = open(mimeTypes)
    fun launch(mimeType: String) = open(arrayOf(mimeType))
}

@Composable
fun rememberInAppDocumentPicker(onResult: (Uri?) -> Unit): InAppDocumentPickerLauncher {
    var mimeTypes by remember { mutableStateOf<Array<String>?>(null) }
    val callback by rememberUpdatedState(onResult)
    val launcher = remember { InAppDocumentPickerLauncher { mimeTypes = it } }
    mimeTypes?.let { requested ->
        val audio = requested.any { it.startsWith("audio/") }
        if (audio) {
            DeviceAudioPickerDialog(
                title = "Archivos de audio", confirmLabel = "Usar audio",
                maxClipDurationMs = null, allowFullTrack = true, defaultToFullTrack = true,
                enableOpenCatalog = false, catalogTracks = emptyList(), showBrowseFiles = false,
                onBrowseFiles = {}, onDismiss = { mimeTypes = null },
                onClipReady = { clip -> mimeTypes = null; callback(clip.uri) },
            )
        } else {
            DocumentPickerSheet(
                pickerTitle = "Elegir archivo",
                allowedExtensions = if (requested.any { it == "application/json" }) setOf("json") else null,
                onDismiss = { mimeTypes = null; callback(null) },
                onPicked = { uri -> mimeTypes = null; callback(uri) },
            )
        }
    }
    return launcher
}
