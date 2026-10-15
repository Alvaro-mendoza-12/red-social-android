package conectate.com.core.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes a complete user-requested copy without launching a document destination picker. */
internal suspend fun savePublicMedia(
    context: Context,
    filename: String,
    mimeType: String,
    checkActive: () -> Unit,
    write: (OutputStream) -> Unit,
): Uri = savePublicFile(context, filename, mimeType, null, checkActive, write)

/** Documents, charts and QR exports belong in Downloads, including SVG images. */
internal suspend fun savePublicDownload(
    context: Context, filename: String, mimeType: String,
    checkActive: () -> Unit, write: (OutputStream) -> Unit,
): Uri = savePublicFile(context, filename, mimeType, Environment.DIRECTORY_DOWNLOADS, checkActive, write)

private suspend fun savePublicFile(
    context: Context, filename: String, mimeType: String, directoryOverride: String?,
    checkActive: () -> Unit, write: (OutputStream) -> Unit,
): Uri = withContext(Dispatchers.IO) {
    require(filename == File(filename).name && filename.isNotBlank())
    checkActive()
    val directory = directoryOverride ?: when {
        mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
        mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_DOWNLOADS
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val collection = when {
            directoryOverride == Environment.DIRECTORY_DOWNLOADS -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mimeType.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mimeType.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/Novvin")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "No se pudo crear el archivo" }
        try {
            resolver.openOutputStream(uri, "w").use { output -> write(checkNotNull(output)) }
            checkActive()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    } else {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        @Suppress("DEPRECATION")
        val destination = File(Environment.getExternalStoragePublicDirectory(directory), "Novvin").apply {
            check(isDirectory || mkdirs())
        }
        // A unique name prevents overwriting any existing user file on legacy Android.
        val target = File.createTempFile(filename.substringBeforeLast('.').take(80) + "_", "." + filename.substringAfterLast('.', "bin"), destination)
        try {
            target.outputStream().use(write)
            checkActive()
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mimeType), null)
            Uri.fromFile(target)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }
}
