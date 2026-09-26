package conectate.com.presentation.messages

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.core.media.resolveOfflineChatExportFile
import conectate.com.core.media.chatMediaExportFilename
import conectate.com.presentation.components.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Export is explicit and independent from retaining a private offline copy. Never use for view-once. */
@Composable
internal fun rememberChatMediaDownload(
    currentAccountId: () -> String?,
    resolve: suspend (String) -> String?
): (MediaItem) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestResolve by rememberUpdatedState(resolve)
    val latestAccountId by rememberUpdatedState(currentAccountId)
    var pendingLocalSource by remember { mutableStateOf<String?>(null) }
    var pendingAccount by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val saveLocal = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destination ->
        val source = pendingLocalSource
        val account = pendingAccount
        pendingLocalSource = null
        pendingAccount = null
        if (destination != null && source != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        check(account != null && latestAccountId() == account) { "La cuenta activa cambió" }
                        val file = resolveOfflineChatExportFile(context.filesDir, source, account)
                        context.contentResolver.openOutputStream(destination, "wt").use { output ->
                            checkNotNull(output) { "No se pudo abrir el destino" }
                            file.inputStream().use { it.copyTo(output) }
                        }
                    }
                    Toast.makeText(context, "Archivo guardado", Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(context, "No se pudo guardar el archivo. Comprueba el espacio y reintenta.", Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        } else {
            busy = false
        }
    }
    return { item ->
        if (!busy) {
            busy = true
            scope.launch {
                var awaitingDestination = false
                try {
                    val account = latestAccountId()?.takeIf { it.isNotBlank() } ?: error("No active account")
                    val source = latestResolve(item.url) ?: error("Multimedia no disponible")
                    check(latestAccountId() == account) { "La cuenta activa cambió" }
                    val filename = chatMediaExportFilename(item.url, item.type, System.currentTimeMillis())
                    if (source.startsWith("file:", ignoreCase = true)) {
                        pendingLocalSource = source
                        pendingAccount = account
                        saveLocal.launch(filename)
                        awaitingDestination = true
                    } else {
                        require(source.startsWith("https://") || source.startsWith("http://"))
                        val request = DownloadManager.Request(Uri.parse(source))
                            .setTitle("Descargando archivo")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                        UbuntuStorageUrl.publicRequestHeaders.forEach { (name, value) -> request.addRequestHeader(name, value) }
                        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                        Toast.makeText(context, "Descarga iniciada", Toast.LENGTH_SHORT).show()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(context, "No se pudo iniciar la descarga. Reintenta.", Toast.LENGTH_LONG).show()
                } finally {
                    if (!awaitingDestination) busy = false
                }
            }
        }
    }
}
