package conectate.com.presentation.profile

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import conectate.com.presentation.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialQrScannerScreen(
    navController: NavController
) {
    var manualPayload by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var launchedOnce by rememberSaveable { mutableStateOf(false) }

    fun openPayload(raw: String) {
        val parsed = SocialQrPayloadParser.parse(raw)
        if (parsed == null) {
            errorMessage = "Este código no pertenece a un perfil o chat de Conectate."
            return
        }
        errorMessage = null
        when (parsed.target) {
            SocialQrTarget.PROFILE -> navController.navigate(Screen.UserProfile.createRoute(parsed.userId))
            SocialQrTarget.CHAT -> navController.navigate(Screen.Chat.createRoute(parsed.userId))
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents?.trim().orEmpty()
        if (contents.isNotBlank()) {
            manualPayload = contents
            openPayload(contents)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanLauncher.launch(socialScanOptions())
        } else {
            errorMessage = "Activa el permiso de cámara o pega el enlace del perfil manualmente."
        }
    }

    fun requestScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            scanLauncher.launch(socialScanOptions())
        }
    }

    LaunchedEffect(launchedOnce) {
        if (!launchedOnce) {
            launchedOnce = true
            requestScan()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Escanear código de amigo") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Encuentra perfiles y chats por QR",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Escanea el QR social de otro usuario para abrir su perfil o iniciar una conversación directa.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = ::requestScan,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Escanear código")
            }

            OutlinedTextField(
                value = manualPayload,
                onValueChange = { manualPayload = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                label = { Text("Pegar enlace o contenido") },
                placeholder = { Text("https://conectate.com/u/...") }
            )

            Button(
                onClick = { openPayload(manualPayload) },
                enabled = manualPayload.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Chat, contentDescription = null)
                Text("Abrir código")
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun socialScanOptions(): ScanOptions {
    return ScanOptions().apply {
        setPrompt("Escanea el QR social de Conectate")
        setBeepEnabled(false)
        setOrientationLocked(false)
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    }
}
