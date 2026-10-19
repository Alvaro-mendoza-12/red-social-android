package conectate.com.presentation.components

import android.net.Uri
import androidx.compose.runtime.*
import conectate.com.presentation.messages.MessageMediaComposer

/** Gallery, camera and confirmation stay in Novvin; Android only grants media access. */
class InAppMediaPickerLauncher internal constructor(private val open: (Set<String>) -> Unit) {
    fun launch(mimeType: String) = launch(arrayOf(mimeType))
    fun launch(mimeTypes: Array<String>) {
        val types = buildSet {
            if (mimeTypes.any { it.startsWith("image/") || it == "*/*" }) add("image")
            if (mimeTypes.any { it.startsWith("video/") || it == "*/*" }) add("video")
        }
        require(types.isNotEmpty()) { "This picker accepts images and videos" }
        open(types)
    }
}

@Composable
fun rememberInAppMediaPicker(onResult: (Uri?) -> Unit): InAppMediaPickerLauncher {
    var types by remember { mutableStateOf<Set<String>?>(null) }
    val callback by rememberUpdatedState(onResult)
    val launcher = remember { InAppMediaPickerLauncher { types = it } }
    types?.let { allowed ->
        MessageMediaComposer(
            initialStep = "gallery", maxItems = 1, allowedMediaTypes = allowed,
            isSending = false, confirmContentDescription = "Usar archivo",
            onDismiss = { types = null; callback(null) },
            onSend = { items, _ -> types = null; callback(items.single().uri) },
        )
    }
    return launcher
}

@Composable
fun rememberInAppMultipleMediaPicker(maxItems: Int = 10, onResult: (List<Uri>) -> Unit): InAppMediaPickerLauncher {
    var types by remember { mutableStateOf<Set<String>?>(null) }
    val callback by rememberUpdatedState(onResult)
    val launcher = remember { InAppMediaPickerLauncher { types = it } }
    types?.let { allowed -> MessageMediaComposer(
        initialStep = "gallery", maxItems = maxItems.coerceIn(1, 10), allowedMediaTypes = allowed,
        isSending = false, confirmContentDescription = "Añadir al proyecto",
        onDismiss = { types = null; callback(emptyList()) },
        onSend = { items, _ -> types = null; callback(items.map { it.uri }) },
    ) }
    return launcher
}
