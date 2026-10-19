package conectate.com.presentation.messages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSelectionToolbar(
    count: Int,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onMore: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    TopAppBar(
        title = { Text("$count seleccionados", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onCancel, enabled = !busy) { Icon(Icons.Default.Close, "Cancelar selección") } },
        actions = {
            IconButton(onClick = onSave, enabled = count > 0 && !busy) { Icon(Icons.Default.Star, "Guardar seleccionados") }
            IconButton(onClick = onForward, enabled = count > 0 && !busy) { Icon(Icons.AutoMirrored.Filled.Forward, "Reenviar seleccionados") }
            IconButton(onClick = onDelete, enabled = count > 0 && !busy) { Icon(Icons.Default.Delete, "Eliminar seleccionados", tint = MaterialTheme.colorScheme.error) }
            if (count == 1 && onMore != null) IconButton(onClick = onMore, enabled = !busy) { Icon(Icons.Default.MoreVert, "Más acciones del mensaje") }
        },
    )
}
