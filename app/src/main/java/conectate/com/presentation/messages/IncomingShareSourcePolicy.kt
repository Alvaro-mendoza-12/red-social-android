package conectate.com.presentation.messages

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import java.io.File
import java.util.UUID

/** Validate before asking a provider for metadata or opening any stream under this app's UID. */
internal fun mayReadIncomingShareSource(context: Context, uri: Uri, trustedCaptureId: String? = null,
    hasReadGrantFlag: Boolean = false): Boolean =
    try {
        when (uri.scheme?.lowercase()) {
            "file" -> {
                val id = trustedCaptureId?.takeIf { UUID.fromString(it).toString().equals(it, true) }
                if (id == null || !uri.authority.isNullOrBlank()) false
                else {
                    val file = uri.path?.let { File(it).canonicalFile }
                    val root = File(context.filesDir, "pending-incoming-share").canonicalFile
                    val capture = File(root, id.lowercase()).canonicalFile
                    // Raw external file paths cannot prove the sender's access. Only our saved task
                    // may restore files inside its own capture, never a symlink outside that root.
                    file != null && capture.isInside(root) && file.isInside(capture)
                }
            }
            "content" -> {
                val authority = uri.authority?.substringAfterLast('@').orEmpty()
                if (!hasReadGrantFlag || authority.isBlank() || authority == "${context.packageName}.provider") false
                else {
                    val provider = context.packageManager.resolveContentProvider(authority, 0)
                    provider?.applicationInfo?.uid != context.applicationInfo.uid &&
                        context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
                }
            }
            else -> false
        }
    } catch (_: Exception) { false }

private fun File.isInside(directory: File): Boolean = path.startsWith(directory.path + File.separator)
