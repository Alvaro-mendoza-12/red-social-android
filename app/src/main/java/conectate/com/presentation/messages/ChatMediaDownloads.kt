package conectate.com.presentation.messages

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import conectate.com.core.media.*
import conectate.com.presentation.components.MediaItem
import conectate.com.util.MediaPlaybackDataSources
import java.io.File
import kotlinx.coroutines.*

@Composable
internal fun rememberChatMediaDownload(currentAccountId: () -> String?, resolve: suspend (String) -> String?): (MediaItem) -> Unit =
    rememberChatMediaExport(currentAccountId, resolve, share = false)

@Composable
internal fun rememberChatMediaShare(currentAccountId: () -> String?, resolve: suspend (String) -> String?): (MediaItem) -> Unit =
    rememberChatMediaExport(currentAccountId, resolve, share = true)

/** Explicit gallery export or Android Sharesheet. Private account copies stay private. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun rememberChatMediaExport(
    currentAccountId: () -> String?, resolve: suspend (String) -> String?, share: Boolean,
): (MediaItem) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestResolve by rememberUpdatedState(resolve)
    val latestAccountId by rememberUpdatedState(currentAccountId)
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<MediaItem, String>?>(null) }

    fun export(item: MediaItem, account: String) {
        busy = true
        scope.launch {
            var shareFile: File? = null
            var delivered = false
            try {
                require(!item.type.startsWith("view_once"))
                val source = latestResolve(item.url) ?: error("Multimedia no disponible")
                val filename = chatMediaExportFilename(item.url, item.type, System.currentTimeMillis())
                val mime = chatMediaExportMimeType(filename)
                val coroutine = currentCoroutineContext()
                val guard = {
                    coroutine.ensureActive()
                    check(latestAccountId()?.trim()?.lowercase() == account) { "La cuenta activa cambió" }
                }
                withContext(Dispatchers.IO) {
                    guard()
                    val local = if (source.startsWith("file:", ignoreCase = true))
                        resolveOfflineChatExportFile(context.filesDir, source, account) else null
                    require(local != null || Uri.parse(source).scheme in setOf("https", "http"))
                    val write: (java.io.OutputStream) -> Unit = { output ->
                        if (local != null) local.inputStream().use { copyMediaWithLimit(it, output, MAX_EXPORT_BYTES, guard) }
                        else DataSourceInputStream(MediaPlaybackDataSources.httpFactory().createDataSource(), DataSpec(Uri.parse(source))).use {
                            it.open()
                            copyMediaWithLimit(it, output, MAX_EXPORT_BYTES, guard)
                        }
                    }
                    if (share) {
                        val directory = File(context.cacheDir, "file-provider/media-exports").apply { check(isDirectory || mkdirs()) }
                        directory.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
                        shareFile = File.createTempFile("Novvin_", ".${filename.substringAfterLast('.')}", directory)
                        checkNotNull(shareFile).outputStream().use(write)
                        guard()
                    } else savePublicMedia(context, filename, mime, guard, write)
                }
                guard()
                if (share) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", checkNotNull(shareFile))
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("Multimedia", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Compartir con"))
                    delivered = true
                } else Toast.makeText(context, "Archivo guardado", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "No se pudo ${if (share) "compartir" else "guardar"}. Comprueba la conexión y el espacio disponible.", Toast.LENGTH_LONG).show()
            } finally {
                if (!delivered) shareFile?.delete()
                busy = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pending
        pending = null
        busy = false
        if (granted && request != null) export(request.first, request.second)
        else Toast.makeText(context, "Permite guardar archivos para descargar la imagen o el video.", Toast.LENGTH_LONG).show()
    }
    return { item ->
        if (!busy) {
            val account = latestAccountId()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (account != null && !item.type.startsWith("view_once")) {
                if (!share && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    busy = true
                    pending = item to account
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else export(item, account)
            }
        }
    }
}

private const val MAX_EXPORT_BYTES = 256L * 1024 * 1024
