package conectate.com.presentation.videos

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Retain read access only to documents explicitly chosen in the system picker. */
internal fun retainVideoDocumentAccess(context: Context, uri: Uri): Boolean =
    retainVideoDocumentAccess(uri) { chosen, flags -> context.contentResolver.takePersistableUriPermission(chosen, flags) }

internal fun retainVideoDocumentAccess(uri: Uri, takeGrant: (Uri, Int) -> Unit): Boolean {
    if (uri.scheme != "content") return uri.scheme == "file"
    return try {
        takeGrant(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) { false }
      catch (_: IllegalArgumentException) { false }
}
