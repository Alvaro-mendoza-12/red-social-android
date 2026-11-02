package conectate.com.presentation.messages

import android.content.Context
import android.net.Uri
import java.io.File

/** Validate before asking a provider for metadata or opening any stream under this app's UID. */
internal fun mayReadIncomingShareSource(context: Context, uri: Uri, trustedCaptureId: String? = null): Boolean =
    try {
        when (uri.scheme?.lowercase()) {
            "file" -> {
                if (!uri.authority.isNullOrBlank()) false
                else {
                    val file = uri.path?.let { File(it).canonicalFile }
                    val capture = trustedCaptureId?.let {
                        File(context.filesDir, "pending-incoming-share/$it").canonicalFile
                    }
                    val privateRoots = (listOfNotNull(
                        context.dataDir, context.filesDir, context.cacheDir, context.noBackupFilesDir,
                        context.createDeviceProtectedStorageContext()?.dataDir,
                    ) + context.getExternalFilesDirs(null).orEmpty().filterNotNull() +
                        context.externalCacheDirs.orEmpty().filterNotNull() +
                        context.externalMediaDirs.orEmpty().filterNotNull()).map { it.canonicalFile }
                    file != null && when {
                        capture != null && file.isInside(capture) -> true
                        privateRoots.any { file == it || file.isInside(it) } -> false
                        // Also reject aliases of Android private/system storage and process descriptors.
                        listOf("/data", "/proc", "/sys", "/dev").any {
                            val root = File(it).canonicalFile
                            file == root || file.isInside(root)
                        } -> false
                        else -> true // Legacy file URIs in shared storage remain supported.
                    }
                }
            }
            "content" -> {
                val authority = uri.authority?.substringAfterLast('@').orEmpty()
                if (authority.isBlank() || authority == "${context.packageName}.provider") false
                else {
                    val provider = context.packageManager.resolveContentProvider(authority, 0)
                    provider?.applicationInfo?.uid != context.applicationInfo.uid
                }
            }
            else -> false
        }
    } catch (_: Exception) { false }

private fun File.isInside(directory: File): Boolean = path.startsWith(directory.path + File.separator)
