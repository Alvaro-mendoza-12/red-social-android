package conectate.com.presentation.profile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import conectate.com.data.model.User
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

@Composable
fun SocialProfileQrDialog(
    user: User,
    clipboardManager: ClipboardManager,
    context: Context,
    onDismiss: () -> Unit,
    onOpenChat: (() -> Unit)? = null
) {
    val userName = user.display_name ?: user.full_name ?: user.username ?: "Perfil Conectate"
    val profileUrl = remember(user.id) { SocialQrLinks.profileUrl(user.id) }
    val chatUrl = remember(user.id) { SocialQrLinks.chatUrl(user.id) }
    val profileQr = remember(profileUrl) { createQrBitmap(profileUrl) }
    val chatQr = remember(chatUrl) { createQrBitmap(chatUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("QR social", fontWeight = FontWeight.Bold)
                Text(userName, style = MaterialTheme.typography.bodyMedium)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                QrPreviewBlock(
                    title = "Abrir perfil",
                    payload = profileUrl,
                    bitmap = profileQr,
                    icon = Icons.Default.Person,
                    onCopy = { clipboardManager.setText(AnnotatedString(profileUrl)) },
                    onShare = { shareText(context, profileUrl) }
                )
                QrPreviewBlock(
                    title = "Abrir chat",
                    payload = chatUrl,
                    bitmap = chatQr,
                    icon = Icons.Default.Chat,
                    onCopy = { clipboardManager.setText(AnnotatedString(chatUrl)) },
                    onShare = { shareText(context, chatUrl) }
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onOpenChat?.let { openChat ->
                    OutlinedButton(onClick = openChat) {
                        Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Chat")
                    }
                }
                Button(onClick = onDismiss) {
                    Text("Listo")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
    )
}

@Composable
private fun QrPreviewBlock(
    title: String,
    payload: String,
    bitmap: Bitmap,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    androidx.compose.material3.Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(title, fontWeight = FontWeight.Bold)
                }
                Row {
                    OutlinedButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier.size(188.dp)
            )
            Text(
                text = payload,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun createQrBitmap(content: String, size: Int = 640): Bitmap {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}

private fun shareText(context: Context, value: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, value)
    }
    context.startActivity(Intent.createChooser(intent, "Compartir perfil"))
}
