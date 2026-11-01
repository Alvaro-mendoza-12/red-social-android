package conectate.com.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import conectate.com.data.model.Comment

/** Keep the edited text until the server confirms the save. */
@Composable
fun CommentEditDialog(comment: Comment, saving: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable(comment.id) { mutableStateOf(comment.content) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Editar comentario") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, enabled = !saving,
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = !saving && text.isNotBlank() && text != comment.content) {
                Text(if (saving) "Guardando…" else "Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancelar") } },
    )
}
