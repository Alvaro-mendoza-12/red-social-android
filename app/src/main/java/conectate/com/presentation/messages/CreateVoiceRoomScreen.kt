package conectate.com.presentation.messages

import android.content.ClipData
import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.unit.sp
import conectate.com.data.model.VoiceRoomModes
import conectate.com.data.repository.VoiceRoomRepository
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVoiceRoomScreen(
    navController: NavController,
    viewModel: CreateVoiceRoomViewModel = hiltViewModel()
) {
    var createdRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    var createdRoomCode by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var roomMode by rememberSaveable { mutableStateOf(VoiceRoomModes.CASUAL) }
    var isPublic by rememberSaveable { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    val error by viewModel.error.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Crear Sala de Voz") },
                navigationIcon = {
                    if (createdRoomId == null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        if (createdRoomId != null) {
            // Success Screen
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "¡Sala creada con éxito!",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Código de sala",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                // ID Display Card
                if (!createdRoomCode.isNullOrBlank()) VoiceRoomCodeCard(
                    code = createdRoomCode.orEmpty(),
                    onCopy = {
                        createdRoomCode?.let { code ->
                            clipboardScope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("Código de sala", code))
                                )
                                snackbarHostState.showSnackbar("Código copiado")
                            }
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = {
                        // Navigate to the room directly
                        // We pop back to clear creation screen from backstack then navigate
                         navController.popBackStack() 
                         navController.navigate(conectate.com.presentation.navigation.Screen.RoomDetail.createRoute(createdRoomId!!))
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                ) {
                    Text("Entrar a la Sala")
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                 TextButton(
                    onClick = { navController.popBackStack() }
                ) {
                    Text("Volver a la lista")
                }
            }
        } else {
            // Creation Form
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre de la sala") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Text("Modalidad", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedCard(
                        onClick = { roomMode = VoiceRoomModes.CASUAL },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = roomMode == VoiceRoomModes.CASUAL,
                                onClick = { roomMode = VoiceRoomModes.CASUAL }
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text("Sala casual", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(
                                    "Todos pueden conversar, activar cámara y compartir pantalla.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    OutlinedCard(
                        onClick = { roomMode = VoiceRoomModes.STAGE },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = roomMode == VoiceRoomModes.STAGE,
                                onClick = { roomMode = VoiceRoomModes.STAGE }
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text("Escenario", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(
                                    "La audiencia escucha y solicita la palabra; moderadores controlan quién habla.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Sala Pública", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Cualquiera puede unirse a escuchar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }

                if (!isPublic) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Contraseña de la sala") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        viewModel.createRoom(
                            name,
                            description,
                            isPublic,
                            if (isPublic) null else password,
                            roomMode
                        ) { room ->
                            createdRoomCode = room.readableId
                            createdRoomId = room.id
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !isCreating && name.isNotBlank(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Crear Sala")
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceRoomCodeCard(code: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onCopy, modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val density = LocalDensity.current
            // Fit the entire short code even with enlarged text; never hide digits.
            val fontSize = minOf(32f, (maxWidth.value - 40f).coerceAtLeast(1f) /
                (code.length.coerceAtLeast(1) * 0.68f * density.fontScale)).sp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(code, modifier = Modifier.weight(1f), fontSize = fontSize,
                    fontFamily = FontFamily.Monospace, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1, softWrap = false,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.width(16.dp))
                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar código de sala")
            }
        }
    }
}
