package conectate.com.presentation.profile

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import conectate.com.data.localfallback.AdminRecoveryEvidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun RecoveryEvidenceSection(files: List<AdminRecoveryEvidence>, onLoad: suspend (String) -> Result<ByteArray>) {
    if (files.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<File?>(null) }
    var imageName by remember { mutableStateOf("") }
    Text("Archivos de evidencia (${files.size})", style = MaterialTheme.typography.titleMedium)
    Text("Privados · disponibles durante 30 días", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    files.forEach { evidence ->
        OutlinedButton(onClick = {
            if (loading != null) return@OutlinedButton
            loading = evidence.id; error = null
            scope.launch {
                try {
                    val bytes = onLoad(evidence.id).getOrThrow()
                    val file = withContext(Dispatchers.IO) {
                        val folder = File(context.cacheDir, "file-provider/recovery-evidence").apply { mkdirs() }
                        folder.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 60 * 60_000 }?.forEach { it.delete() }
                        val extension = when (evidence.mime) {
                            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"
                            "application/pdf" -> "pdf"; "text/plain" -> "txt"; "video/webm" -> "webm"
                            "audio/ogg" -> "ogg"; "audio/wav" -> "wav"; "audio/mpeg" -> "mp3"; "audio/mp4" -> "m4a"
                            else -> "mp4"
                        }
                        File(folder, "${evidence.id}.$extension").apply { writeBytes(bytes) }
                    }
                    if (evidence.mime.startsWith("image/")) { image = file; imageName = evidence.name }
                    else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, evidence.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                } catch (failure: Exception) { error = failure.message ?: "No se pudo abrir la evidencia. Comprueba tu conexión o instala un visor compatible." }
                finally { loading = null }
            }
        }, enabled = loading == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (loading == evidence.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Abrir ${evidence.name} · ${((evidence.size + 1023) / 1024)} KB", maxLines = 3)
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    image?.let { file -> AlertDialog(onDismissRequest = { image = null },
        title = { Text(imageName, maxLines = 2) },
        text = { AsyncImage(model = file, contentDescription = imageName, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 420.dp)) },
        confirmButton = { TextButton(onClick = { image = null }) { Text("Cerrar") } }) }
}
