package conectate.com.presentation.social

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import conectate.com.data.model.Moment

@Composable
internal fun MomentViewingTools(
    moment: Moment,
    automatic: Boolean,
    onAutomaticChanged: (Boolean) -> Unit,
    onMenuChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MomentDownloadViewModel = hiltViewModel(),
) {
    var showMenu by remember { mutableStateOf(false) }
    var selectingFile by rememberSaveable { mutableStateOf(false) }
    val saving by viewModel.saving.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val writePermission = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        selectingFile = false
        if (granted) viewModel.savePreparedDirect() else viewModel.saveTo(null)
    }
    val share = conectate.com.presentation.messages.rememberChatMediaShare(viewModel::currentAccountId) {
        conectate.com.util.UbuntuMedia.resolveUrl(it)
    }
    val savePhoto = conectate.com.presentation.messages.rememberChatMediaDownload(viewModel::currentAccountId) {
        conectate.com.util.UbuntuMedia.resolveUrl(it)
    }
    LaunchedEffect(showMenu, selectingFile) { onMenuChanged(showMenu || selectingFile) }
    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }
    IconButton(onClick = { showMenu = true }, modifier = modifier) {
        Icon(Icons.Default.MoreVert, contentDescription = "Opciones del momento", tint = Color.White)
    }
    if (showMenu) AlertDialog(
        onDismissRequest = { showMenu = false },
        title = { Text("Ver a tu ritmo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Avanzar automáticamente", Modifier.weight(1f))
                    Switch(checked = automatic, onCheckedChange = onAutomaticChanged)
                }
                Text("Desactívalo para quedarte en cada momento. Toca sus lados para retroceder o continuar.")
                if (moment.isVideoMoment()) {
                    Text("Guarda el archivo de video original. Los textos superpuestos y la música añadida por separado no se incluyen.")
                    FilledTonalButton(
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (viewModel.prepare(moment)) {
                                showMenu = false
                                if (android.os.Build.VERSION.SDK_INT < 29 &&
                                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    selectingFile = true
                                    writePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else viewModel.savePreparedDirect()
                            }
                        },
                    ) { Text(if (saving) "Guardando video…" else "Descargar video") }
                }
                if (!moment.isVideoMoment()) FilledTonalButton(onClick = {
                    showMenu = false
                    savePhoto(conectate.com.presentation.components.MediaItem(id = moment.id, url = moment.mediaUrl, type = "image"))
                }) { Text("Descargar foto") }
                TextButton(onClick = {
                    showMenu = false
                    share(conectate.com.presentation.components.MediaItem(id = moment.id, url = moment.mediaUrl, type = if (moment.isVideoMoment()) "video" else "image"))
                }) { Text("Compartir con otra aplicación") }
                if (saving) TextButton(onClick = viewModel::cancel) { Text("Cancelar descarga") }
            }
        },
        confirmButton = { TextButton(onClick = { showMenu = false }) { Text("Listo") } },
    )
}
