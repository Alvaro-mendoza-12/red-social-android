package conectate.com.presentation.social

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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

/** Keep the chosen name and MIME together at launch, even if the current moment later changes. */
private class CreateMomentDocument : ActivityResultContract<MomentExportTarget, Uri?>() {
    override fun createIntent(context: Context, input: MomentExportTarget): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

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
    val createDocument = rememberLauncherForActivityResult(remember { CreateMomentDocument() }) { uri ->
        selectingFile = false
        viewModel.saveTo(uri)
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
                                selectingFile = true
                                showMenu = false
                                val target = momentExportTarget(moment.id, moment.mediaUrl, moment.mediaType)
                                runCatching { createDocument.launch(target) }.onFailure {
                                    selectingFile = false
                                    viewModel.saveTo(null)
                                    android.widget.Toast.makeText(context, "No hay una aplicación disponible para guardar archivos.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    ) { Text(if (saving) "Guardando video…" else "Descargar video") }
                }
                if (saving) TextButton(onClick = viewModel::cancel) { Text("Cancelar descarga") }
            }
        },
        confirmButton = { TextButton(onClick = { showMenu = false }) { Text("Listo") } },
    )
}
