package conectate.com.presentation.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import conectate.com.data.model.LiveDrawPoint
import conectate.com.data.model.LiveDrawPacket
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
fun LiveDrawGuessDialog(
    liveId: String,
    currentUserId: String,
    isHost: Boolean,
    participantIds: List<String>,
    onDismiss: () -> Unit,
    viewModel: LiveDrawGuessViewModel = hiltViewModel()
) {
    val snapshot by viewModel.gameState.collectAsStateWithLifecycle()
    val drawing by viewModel.drawPackets.collectAsStateWithLifecycle()
    val sending by viewModel.busy.collectAsStateWithLifecycle()
    val failure by viewModel.error.collectAsStateWithLifecycle()
    val answer by viewModel.guessResult.collectAsStateWithLifecycle()
    val accountVisitGeneration by viewModel.accountVisitGeneration.collectAsStateWithLifecycle()
    val ownsVisit = viewModel.isCurrentVisit(liveId, currentUserId)
    val state = snapshot.takeIf { ownsVisit }
    val packets = if (ownsVisit) drawing else emptyList()
    val busy = ownsVisit && sending
    val error = failure.takeIf { ownsVisit }
    val guessResult = answer.takeIf { ownsVisit && it?.state?.round?.id == state?.round?.id }
    var guess by remember(liveId, currentUserId, accountVisitGeneration) { mutableStateOf("") }
    var remainingSeconds by remember(liveId, currentUserId, accountVisitGeneration) { mutableIntStateOf(0) }

    LifecycleResumeEffect(liveId, currentUserId, accountVisitGeneration) {
        val binding = viewModel.bind(liveId)
        onPauseOrDispose { viewModel.unbind(binding) }
    }
    LaunchedEffect(state?.round?.endsAt, accountVisitGeneration) {
        val deadline = runCatching { Instant.parse(state?.round?.endsAt).toEpochMilli() }.getOrNull()
        if (deadline == null) {
            remainingSeconds = 0
            return@LaunchedEffect
        }
        while (true) {
            remainingSeconds = ((deadline - System.currentTimeMillis()).coerceAtLeast(0L) / 1_000L).toInt()
            if (remainingSeconds <= 0) break
            delay(250L)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 760.dp)
                .navigationBarsPadding()
                .imePadding(),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dibujo y adivinanza", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "La palabra, el tiempo y los puntos los controla el servidor.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }

                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                error?.let {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                            )
                            TextButton(onClick = viewModel::clearFeedback) { Text("Ocultar") }
                        }
                    }
                }

                when (val current = state) {
                    null -> {
                        if (isHost) {
                            Text(
                                if (participantIds.isEmpty())
                                    "Conecta al menos un invitado por voz o cámara antes de crear la partida."
                                else
                                    "Se invitará a ${participantIds.distinct().size} participante(s) conectado(s)."
                            )
                            Button(
                                enabled = participantIds.isNotEmpty() && !busy,
                                onClick = { viewModel.create(participantIds.distinct()) }
                            ) { Text("Crear partida") }
                        } else {
                            Text("El anfitrión todavía no ha creado una partida para ti.")
                        }
                    }
                    else -> {
                        val ownPlayer = current.players.firstOrNull {
                            it.userId.equals(currentUserId, ignoreCase = true)
                        }
                        val isDrawer = current.round?.drawerUserId.equals(currentUserId, ignoreCase = true)
                        Text(
                            when (current.game.status) {
                                "lobby" -> "Esperando participantes"
                                "active" -> "Ronda ${current.round?.roundNumber ?: 0} de ${current.game.totalRounds} · ${remainingSeconds}s"
                                "completed" -> "Partida finalizada"
                                else -> "Partida cancelada"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )

                        Scoreboard(players = current.players)

                        when (current.game.status) {
                            "lobby" -> {
                                if (!isHost && ownPlayer?.status == "invited") {
                                    Button(enabled = !busy, onClick = viewModel::accept) {
                                        Text("Aceptar invitación")
                                    }
                                }
                                if (isHost) {
                                    Button(
                                        enabled = current.players.count { it.status == "accepted" } >= 2 && !busy,
                                        onClick = viewModel::start
                                    ) { Text("Iniciar") }
                                }
                            }
                            "active" -> {
                                if (isDrawer) {
                                    Text(
                                        text = current.privatePrompt?.label?.let { "Dibuja: $it" }
                                            ?: "Esperando palabra privada…",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text("Adivina el dibujo. No compartas datos personales.")
                                }
                                DrawCanvas(
                                    packets = packets,
                                    enabled = isDrawer && current.privatePrompt != null && remainingSeconds > 0,
                                    roundId = current.round?.id.orEmpty(),
                                    onStroke = viewModel::sendStroke
                                )
                                if (!isDrawer) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = guess,
                                            onValueChange = { guess = it.take(72) },
                                            label = { Text("Tu respuesta") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            enabled = guess.isNotBlank() && !busy && remainingSeconds > 0,
                                            onClick = {
                                                viewModel.submitGuess(guess)
                                                guess = ""
                                            }
                                        ) { Text("Enviar") }
                                    }
                                    guessResult?.let { result ->
                                        Text(
                                            if (result.correct) "¡Correcto! +${result.awardedPoints} puntos"
                                            else "Todavía no. Sigue intentando.",
                                            color = if (result.correct) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (isHost && remainingSeconds <= 0) {
                                    OutlinedButton(enabled = !busy, onClick = viewModel::advance) {
                                        Text("Siguiente ronda")
                                    }
                                }
                            }
                            "completed", "canceled" -> {
                                if (isHost && participantIds.isNotEmpty()) {
                                    Button(
                                        enabled = !busy,
                                        onClick = { viewModel.create(participantIds.distinct()) }
                                    ) { Text("Nueva partida") }
                                }
                            }
                        }
                        if (isHost && current.game.status in setOf("lobby", "active")) {
                            TextButton(enabled = !busy, onClick = viewModel::cancel) {
                                Text("Cancelar partida", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Scoreboard(players: List<conectate.com.data.model.LiveDrawGuessPlayer>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        players.forEach { player ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp).background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        CircleShape
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(player.rank?.toString() ?: "·")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    player.userId.take(18),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(if (player.status == "accepted") "${player.score} pts" else "Pendiente")
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun DrawCanvas(
    packets: List<LiveDrawPacket>,
    enabled: Boolean,
    roundId: String,
    onStroke: (List<LiveDrawPoint>) -> Unit
) {
    var currentStroke by remember(roundId) { mutableStateOf<List<LiveDrawPoint>>(emptyList()) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(Color.White, RoundedCornerShape(16.dp))
            .pointerInput(enabled, roundId) {
                if (!enabled) return@pointerInput
                fun normalized(position: Offset): LiveDrawPoint = LiveDrawPoint(
                    x = (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                    y = (position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                )
                detectDragGestures(
                    onDragStart = { position -> currentStroke = listOf(normalized(position)) },
                    onDragEnd = {
                        val completed = currentStroke
                        currentStroke = emptyList()
                        if (completed.isNotEmpty()) onStroke(completed)
                    },
                    onDragCancel = { currentStroke = emptyList() },
                    onDrag = { change, _ ->
                        if (currentStroke.size < 64) currentStroke = currentStroke + normalized(change.position)
                    }
                )
            }
    ) {
        (packets + if (currentStroke.isNotEmpty()) listOf(LiveDrawPacket(-1L, points = currentStroke)) else emptyList())
            .forEach { packet ->
                val color = if (packet.tool == "eraser") Color.White else runCatching {
                    Color(android.graphics.Color.parseColor(packet.color))
                }.getOrDefault(Color.Black)
                val width = (packet.width * size.minDimension).coerceAtLeast(2f)
                if (packet.points.size == 1) {
                    drawCircle(
                        color = color,
                        radius = width / 2f,
                        center = Offset(packet.points[0].x * size.width, packet.points[0].y * size.height)
                    )
                } else {
                    packet.points.zipWithNext().forEach { (from, to) ->
                        drawLine(
                            color = color,
                            start = Offset(from.x * size.width, from.y * size.height),
                            end = Offset(to.x * size.width, to.y * size.height),
                            strokeWidth = width,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
    }
}
