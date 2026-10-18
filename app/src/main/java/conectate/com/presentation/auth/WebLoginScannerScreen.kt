package conectate.com.presentation.auth

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.core.content.ContextCompat
import conectate.com.data.model.StoredAccountSession
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WebLoginScannerScreen(
    navController: NavController,
    initialPairId: String? = null,
    initialNonce: String? = null,
    viewModel: WebLoginScannerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    var attemptedAutoLaunch by rememberSaveable { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim().orEmpty()
        if (contents.isNotBlank()) {
            viewModel.submitPayload(contents, linkAfterScan = true)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanLauncher.launch(defaultScanOptions())
        } else {
            viewModel.onCameraPermissionDenied()
        }
    }

    fun requestScan() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanLauncher.launch(defaultScanOptions())
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(initialPairId, initialNonce) {
        if (!initialPairId.isNullOrBlank() && !initialNonce.isNullOrBlank()) {
            viewModel.loadPairing(initialPairId, initialNonce)
        }
    }

    LaunchedEffect(initialPairId, initialNonce, attemptedAutoLaunch) {
        if (!initialPairId.isNullOrBlank() || !initialNonce.isNullOrBlank() || attemptedAutoLaunch) {
            return@LaunchedEffect
        }
        attemptedAutoLaunch = true
        requestScan()
    }

    val linked = uiState.linkAfterScan && uiState.pairing?.status == "consumed"
    LaunchedEffect(linked) {
        if (linked && navController.previousBackStackEntry != null) {
            android.widget.Toast.makeText(context, "Dispositivo vinculado. Ya puedes usar Novvin Web.", android.widget.Toast.LENGTH_LONG).show()
            navController.popBackStack()
        }
    }
    LaunchedEffect(uiState.errorMessage, uiState.infoMessage, uiState.isLoading, uiState.isSubmitting) {
        scrollState.animateScrollTo(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Acceso web por QR") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshPairing() },
                        enabled = uiState.pairing != null && !uiState.isLoading && !uiState.isSubmitting
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Actualizar estado"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(scrollState)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!uiState.hasAnyAvailableAccount) {
                EmptyAccountState(
                    onBack = { navController.popBackStack() }
                )
                return@Column
            }

            uiState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                FeedbackCard(
                    text = message,
                    icon = Icons.Default.ErrorOutline,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            uiState.infoMessage?.takeIf { it.isNotBlank() }?.let { message ->
                FeedbackCard(
                    text = message,
                    icon = Icons.Default.CheckCircle,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            if (uiState.isLoading || uiState.isSubmitting) {
                LoadingCard()
            }

            if (linked) return@Column
            HeroCard()
            ApprovalAccountCard(
                enabled = !uiState.isLoading && !uiState.isSubmitting,
                storedAccounts = uiState.storedAccounts,
                currentUserId = uiState.currentUserId,
                currentUserEmail = uiState.currentUserEmail,
                selectedAccountId = uiState.selectedAccountId,
                onSelectCurrentSession = viewModel::selectCurrentSession,
                onSelectStoredAccount = viewModel::selectStoredAccount
            )
            ScannerActionsCard(
                onOpenScanner = ::requestScan,
                isBusy = uiState.isLoading || uiState.isSubmitting
            )

            uiState.pairing?.let { pairing ->
                if (!uiState.linkAfterScan || pairing.status !in setOf("pending", "approved", "consumed")) PairingStatusCard(
                    deviceLabel = pairing.deviceLabel,
                    status = pairing.status,
                    approvedByEmail = pairing.approvedByEmail,
                    expiresAt = pairing.expiresAt,
                    approvedAt = pairing.approvedAt,
                    rejectedAt = pairing.rejectedAt,
                    consumedAt = pairing.consumedAt
                )

                if (pairing.status == "pending" && (!uiState.linkAfterScan || uiState.errorMessage != null)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = viewModel::rejectPairing,
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isSubmitting
                        ) {
                            Text("Rechazar")
                        }
                        Button(
                            onClick = viewModel::approvePairing,
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isSubmitting
                        ) {
                            if (uiState.isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Aprobar acceso")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun defaultScanOptions(): ScanOptions {
    return ScanOptions().apply {
        setPrompt("Escanea el QR de Novvin Web")
        setBeepEnabled(false)
        setOrientationLocked(true)
        setCaptureActivity(PortraitQrCaptureActivity::class.java)
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    }
}

@Composable
private fun HeroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "Conecta Novvin Web de forma segura",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Abre Novvin Web en tu computadora y escanea su QR. Se vinculará automáticamente con la cuenta que elegiste.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyAccountState(
    onBack: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "No hay cuentas disponibles para aprobar este QR",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Primero inicia sesión en la APK o guarda una cuenta en este dispositivo. Luego vuelve a abrir el acceso por QR.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(onClick = onBack) {
                Text("Volver")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalAccountCard(
    enabled: Boolean,
    storedAccounts: List<StoredAccountSession>,
    currentUserId: String?,
    currentUserEmail: String?,
    selectedAccountId: String?,
    onSelectCurrentSession: () -> Unit,
    onSelectStoredAccount: (String) -> Unit
) {
    val currentNormalizedEmail = currentUserEmail?.trim()?.lowercase()
    val currentNormalizedId = currentUserId?.trim()?.lowercase()
    val filteredStoredAccounts = remember(storedAccounts, currentNormalizedEmail, currentNormalizedId) {
        storedAccounts.filterNot { account ->
            (!currentNormalizedId.isNullOrBlank() && account.user_id == currentNormalizedId) ||
                (!currentNormalizedEmail.isNullOrBlank() && account.email.equals(currentNormalizedEmail, ignoreCase = true))
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ManageAccounts,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Cuenta para vincular",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Esta es la cuenta que usarás en la computadora.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!currentNormalizedEmail.isNullOrBlank() || !currentNormalizedId.isNullOrBlank()) {
                    FilterChip(
                        enabled = enabled,
                        selected = selectedAccountId == null,
                        onClick = onSelectCurrentSession,
                        label = {
                            Text(
                                currentUserEmail?.takeIf { it.isNotBlank() } ?: "Sesión actual"
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }

                filteredStoredAccounts.forEach { account ->
                    FilterChip(
                        enabled = enabled,
                        selected = selectedAccountId == account.id,
                        onClick = { onSelectStoredAccount(account.id) },
                        label = {
                            Text(
                                account.display_name?.takeIf { it.isNotBlank() }
                                    ?: account.email
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerActionsCard(
    onOpenScanner: () -> Unit,
    isBusy: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Button(
                onClick = onOpenScanner,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBusy
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text("Escanear QR ahora")
            }

        }
    }
}

@Composable
private fun FeedbackCard(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor
            )
            Text(
                text = text,
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                text = "QR escaneado. Vinculando dispositivo…",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PairingStatusCard(
    deviceLabel: String?,
    status: String,
    approvedByEmail: String?,
    expiresAt: String?,
    approvedAt: String?,
    rejectedAt: String?,
    consumedAt: String?
) {
    val statusLabel = when (status.trim().lowercase()) {
        "pending" -> "Pendiente de aprobación"
        "approved" -> "Aprobado"
        "rejected" -> "Rechazado"
        "consumed" -> "Consumido por la web"
        "expired" -> "Expirado"
        else -> status
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Solicitud detectada",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            StatusLine(
                label = "Estado",
                value = statusLabel
            )
            StatusLine(
                label = "Dispositivo",
                value = deviceLabel ?: "Navegador sin nombre"
            )
            StatusLine(
                label = "Expira",
                value = formatDateForUi(expiresAt) ?: "Sin fecha"
            )
            approvedByEmail?.takeIf { it.isNotBlank() }?.let {
                StatusLine(label = "Aprobado por", value = it)
            }
            approvedAt?.takeIf { it.isNotBlank() }?.let {
                StatusLine(label = "Aprobado el", value = formatDateForUi(it) ?: it)
            }
            rejectedAt?.takeIf { it.isNotBlank() }?.let {
                StatusLine(label = "Rechazado el", value = formatDateForUi(it) ?: it)
            }
            consumedAt?.takeIf { it.isNotBlank() }?.let {
                StatusLine(label = "Usado en web", value = formatDateForUi(it) ?: it)
            }
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatDateForUi(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        Instant.parse(value)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    }.getOrNull()
}
