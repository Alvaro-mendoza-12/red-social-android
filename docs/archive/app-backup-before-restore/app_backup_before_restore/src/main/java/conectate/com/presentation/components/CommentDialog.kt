package conectate.com.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CommentDialog(
    onDismiss: () -> Unit,
    onSendComment: (String) -> Unit
) {
    var commentText by remember { mutableStateOf("") }
    val isEnabled = commentText.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escribir comentario") },
        text = {
            OutlinedTextField(
                value = commentText,
                onValueChange = { commentText = it },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("Escribe tu comentario aquí...") },
                label = { Text("Comentario") }
            )
        },
        confirmButton = {
            Button(
                onClick = { onSendComment(commentText) },
                enabled = isEnabled
            ) {
                Text("Enviar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
