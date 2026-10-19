package conectate.com.presentation.messages

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface CollectionSharingEntryPoint {
    fun users(): conectate.com.data.repository.UserRepository
    fun auth(): conectate.com.data.supabase.AuthService
    fun api(): conectate.com.data.localfallback.LocalFallbackApiClient
}

@Composable
fun CollectionShareDialog(
    title: String,
    link: String,
    navController: androidx.navigation.NavController? = null,
    canRevoke: Boolean = false,
    onScan: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isCollection = remember(link) { android.net.Uri.parse(link).pathSegments.firstOrNull() in setOf("group", "community") }
    val scope = rememberCoroutineScope()
    val services = remember(context) { dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, CollectionSharingEntryPoint::class.java) }
    var internalShare by remember(link) { mutableStateOf(false) }
    var revokeConfirm by remember(link) { mutableStateOf(false) }
    var busy by remember(link) { mutableStateOf(false) }
    var error by remember(link) { mutableStateOf<String?>(null) }
    val qrBitmap = remember(link) { generateCollectionQr(link) }
    if (internalShare && navController != null) conectate.com.presentation.components.InternalShareDialog(
        userRepository = services.users(), onDismiss = { internalShare = false }, onShareWithUser = { user ->
            internalShare = false; onDismiss()
            navController.navigate(conectate.com.presentation.navigation.Screen.Chat.createRoute(user.id, initialMessage = "Te invito a unirte:\n$link"))
        }
    )
    if (revokeConfirm) AlertDialog(onDismissRequest = { if (!busy) revokeConfirm = false },
        title = { Text("¿Revocar las invitaciones?") }, text = { Text("Los enlaces y códigos QR anteriores dejarán de permitir nuevas entradas. Los integrantes actuales conservan su acceso.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            val account = services.auth().getCurrentUserId()
            val result = runCatching {
                val uri = android.net.Uri.parse(link)
                val token = services.auth().getAccessTokenAwait() ?: kotlin.error("Inicia sesión de nuevo.")
                services.api().revokeCollectionInvites(token, if (uri.pathSegments.first() == "group") "groups" else "communities", uri.pathSegments[1]).getOrThrow()
            }
            busy = false; revokeConfirm = false
            if (account == services.auth().getCurrentUserId()) result.onSuccess { onDismiss() }.onFailure { error = "No se pudieron revocar los enlaces. Inténtalo de nuevo." }
        } }) { Text(if (busy) "Revocando…" else "Revocar enlaces") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { revokeConfirm = false }) { Text("Cancelar") } }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Código QR para compartir",
                    modifier = Modifier.size(240.dp)
                )
                Text(if (isCollection) "Escanea para ver la invitación antes de unirte. Caduca en 72 horas." else "Escanea este código o comparte el enlace para abrir el perfil.")
                TextButton(onClick = {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText(title, link))
                    android.widget.Toast.makeText(context, "Enlace copiado", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Copiar enlace") }
                if (navController != null) Button(onClick = { internalShare = true }) { Text("Enviar en Novvin") }
                TextButton(enabled = !busy, onClick = { scope.launch {
                    busy = true
                    runCatching {
                        val uri = withContext(Dispatchers.IO) {
                            val directory = java.io.File(context.cacheDir, "file-provider/media-exports").apply { mkdirs() }
                            directory.listFiles()?.filter { it.name.startsWith("invite-qr-") && System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                            val file = java.io.File(directory, "invite-qr-${java.util.UUID.randomUUID()}.png")
                            file.outputStream().use { qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, link)
                            clipData = android.content.ClipData.newRawUri("Invitación QR", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Compartir código QR"))
                    }.onFailure { error = "No se pudo compartir la imagen del QR." }
                    busy = false
                } }) { Text("Compartir QR como imagen") }
                if (canRevoke) TextButton(enabled = !busy, onClick = { revokeConfirm = true }) { Text("Revocar enlaces anteriores") }
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                }
                context.startActivity(Intent.createChooser(intent, title))
            }) {
                Icon(Icons.Default.Share, contentDescription = null)
                Text("Compartir")
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (onScan != null) {
                    TextButton(onClick = onScan) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Text("Escanear")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        }
    )
}

private fun generateCollectionQr(content: String): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 640, 640)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}
