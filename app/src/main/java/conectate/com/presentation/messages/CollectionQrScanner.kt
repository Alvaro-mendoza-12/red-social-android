package conectate.com.presentation.messages

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import conectate.com.presentation.auth.PortraitQrCaptureActivity
import conectate.com.presentation.navigation.Screen

@Composable
fun CollectionQrScannerButton(
    navController: NavController,
    modifier: Modifier = Modifier,
    viewModel: CollectionQrViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.trim()?.takeIf { it.isNotBlank() }?.let(viewModel::inspect)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanLauncher.launch(collectionScanOptions())
        } else {
            viewModel.showCameraPermissionError()
        }
    }

    fun startScan() {
        val permissionGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (permissionGranted) {
            scanLauncher.launch(collectionScanOptions())
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    uiState.joinedTarget?.let { target ->
        LaunchedEffect(target) {
            val route = if (target.kind == "group") {
                Screen.GroupDetail.createRoute(target.roomId)
            } else {
                Screen.CommunityDetail.createRoute(target.roomId)
            }
            viewModel.consumeNavigation()
            navController.navigate(route)
        }
    }

    OutlinedIconButton(
        onClick = ::startScan,
        modifier = modifier.size(52.dp),
        shape = CircleShape
    ) {
        Icon(
            imageVector = Icons.Default.QrCodeScanner,
            contentDescription = "Escanear QR de grupo o comunidad"
        )
    }

    when {
        uiState.preview != null -> CollectionPreviewDialog(
            state = uiState,
            onDismiss = viewModel::dismiss,
            onConfirm = viewModel::joinOrOpen
        )
        uiState.isLoading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Buscando invitación") },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {}
        )
        !uiState.error.isNullOrBlank() -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("No se pudo abrir el QR") },
            text = { Text(uiState.error.orEmpty()) },
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) { Text("Entendido") }
            }
        )
    }
}

@Composable
private fun CollectionPreviewDialog(
    state: CollectionQrUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val preview = state.preview ?: return
    val isGroup = preview.kind == "group"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            if (preview.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = preview.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                )
            } else {
                Icon(
                    imageVector = if (isGroup) Icons.Default.Groups else Icons.Default.Public,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp)
                )
            }
        },
        title = {
            Text(
                text = preview.name.ifBlank { if (isGroup) "Grupo" else "Comunidad" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isGroup) "Grupo" else "Comunidad",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                if (preview.description.isNotBlank()) {
                    Text(
                        text = preview.description,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${preview.memberCount} ${if (preview.memberCount == 1) "integrante" else "integrantes"}",
                    fontWeight = FontWeight.SemiBold
                )
                preview.participants.take(5).forEach { participant ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (participant.avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = participant.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp).clip(CircleShape)
                            )
                        }
                        Text(
                            text = participant.name.ifBlank { "Usuario" },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (participant.role in setOf("owner", "creator", "admin")) {
                            Text(
                                text = if (participant.role == "admin") "Admin" else "Creador",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                if (!state.error.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !state.isJoining) {
                Text("Cancelar")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !state.isJoining) {
                if (state.isJoining) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (preview.isMember) "Abrir" else "Unirme")
                }
            }
        }
    )
}

private fun collectionScanOptions(): ScanOptions = ScanOptions().apply {
    setPrompt("Escanea un QR de grupo o comunidad")
    setBeepEnabled(false)
    setOrientationLocked(true)
    setCaptureActivity(PortraitQrCaptureActivity::class.java)
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
}
