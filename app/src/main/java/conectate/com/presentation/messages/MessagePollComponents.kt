package conectate.com.presentation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.Message
import conectate.com.data.model.MessagePollOptionState
import conectate.com.data.model.PollVoteUser
import conectate.com.data.model.MessageReadReceipt
import conectate.com.presentation.components.CircularProfileImage

@Composable
fun PollComposerDialog(
    isSending: Boolean,
    onDismiss: () -> Unit,
    onCreatePoll: (question: String, options: List<String>, allowsMultiple: Boolean) -> Unit
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var allowsMultiple by remember { mutableStateOf(false) }

    val cleanOptions = options.map { it.trim() }.filter { it.isNotBlank() }
    val canSubmit = question.trim().isNotBlank() && cleanOptions.size >= 2 && !isSending

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva encuesta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(300) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pregunta") },
                    singleLine = false,
                    maxLines = 3
                )

                options.forEachIndexed { index, option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = option,
                            onValueChange = { next ->
                                options[index] = next.take(120)
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Opción ${index + 1}") },
                            singleLine = true
                        )
                        if (options.size > 2) {
                            IconButton(onClick = { options.removeAt(index) }) {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Quitar opción")
                            }
                        }
                    }
                }

                if (options.size < 12) {
                    TextButton(onClick = { options.add("") }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Añadir opción")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Permitir varias respuestas")
                    Switch(checked = allowsMultiple, onCheckedChange = { allowsMultiple = it })
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSubmit,
                onClick = { onCreatePoll(question.trim(), cleanOptions, allowsMultiple) }
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.size(8.dp))
                    Text("Creando...")
                } else {
                    Text("Crear encuesta")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun PollMessageCard(
    message: Message,
    pollState: List<MessagePollOptionState>?,
    isFromCurrentUser: Boolean,
    onVote: (optionId: String, selected: Boolean) -> Unit,
    onOptionDetails: (option: MessagePollOptionState) -> Unit = {}
) {
    val question = pollState?.firstOrNull()?.question?.ifBlank { message.content } ?: message.content
    val totalVotes = pollState?.firstOrNull()?.totalVotes ?: 0L
    val allowsMultiple = pollState?.firstOrNull()?.allowsMultiple ?: false
    val isClosed = pollState?.firstOrNull()?.isClosed ?: false

    val background = if (isFromCurrentUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isFromCurrentUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = background,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Poll, contentDescription = null, tint = textColor)
                Spacer(Modifier.size(6.dp))
                Text("Encuesta", color = textColor, style = MaterialTheme.typography.labelMedium)
            }

            Text(
                text = question,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (pollState == null) {
                Text("Cargando opciones...", color = textColor.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            } else if (pollState.isEmpty()) {
                Text("No se pudieron cargar las opciones.", color = textColor.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            } else {
                pollState.forEach { option ->
                    val ratio = if (totalVotes > 0L) (option.voteCount.toFloat() / totalVotes.toFloat()) else 0f
                    val rowBackground = if (option.votedByMe) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    } else {
                        Color.Transparent
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBackground, RoundedCornerShape(10.dp))
                            .clickable {
                                onOptionDetails(option)
                            }
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (option.votedByMe) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = textColor, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.size(4.dp))
                                }
                                Text(option.optionText, color = textColor, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${option.voteCount}", color = textColor, style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.size(6.dp))
                                TextButton(
                                    onClick = { onVote(option.optionId, !option.votedByMe) },
                                    enabled = !isClosed
                                ) {
                                    Text(if (option.votedByMe) "Quitar" else "Votar", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildString {
                            append("${totalVotes} votos")
                            if (allowsMultiple) append(" · múltiple")
                            if (isClosed) append(" · cerrada")
                        },
                        color = textColor.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        message.created_at?.let { timestamp ->
                            val time = timestamp.trim().replace(" ", "T")
                                .let { if (!it.contains("Z") && !it.contains("+")) "${it}Z" else it }
                                .substringAfter("T").substringBefore(".").take(5)

                            Text(
                                text = time,
                                color = textColor.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        if (isFromCurrentUser) {
                            Spacer(Modifier.size(4.dp))
                            val isRead = message.status == "read"
                            val isDelivered = message.status == "delivered" || message.status == "received"
                            
                            Icon(
                                imageVector = when (message.status) {
                                    "read", "delivered", "received" -> Icons.Default.DoneAll
                                    "sent" -> Icons.Default.Done
                                    else -> Icons.Default.AccessTime
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (isRead) Color(0xFF34B7F1) else textColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PollOptionVotersDialog(
    title: String,
    voteCount: Long,
    voters: List<PollVoteUser>,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title ($voteCount votos)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    isLoading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Cargando votantes...")
                        }
                    }
                    voters.isEmpty() -> {
                        Text("Aún no hay votos en esta opción.")
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(voters) { voter ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProfileImage(
                                        imageUrl = voter.avatarUrl.orEmpty(),
                                        size = 28.dp,
                                        fallbackName = voter.fullName?.ifBlank { null }
                                            ?: voter.username?.ifBlank { null }
                                            ?: voter.userId
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Column {
                                        Text(
                                            text = voter.fullName?.ifBlank { null }
                                                ?: voter.username?.ifBlank { null }
                                                ?: voter.userId.take(8),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        voter.username?.takeIf { it.isNotBlank() }?.let { username ->
                                            Text(
                                                text = "@$username",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
fun MessageReadInfoDialog(
    message: Message,
    receipts: List<MessageReadReceipt>,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Información del mensaje") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Estado: ${message.status.ifBlank { "sent" }}")
                Text("Enviado: ${formatInfoDate(message.created_at)}")

                if (isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Cargando quién vio...")
                    }
                } else if (receipts.isEmpty()) {
                    Text("Aún nadie lo ha leído.")
                } else {
                    Text("Leído por (${receipts.count { !it.readAt.isNullOrBlank() }}):", fontWeight = FontWeight.SemiBold)
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                        items(receipts) { receipt ->
                            val name = receipt.fullName?.takeIf { it.isNotBlank() }
                                ?: receipt.username?.takeIf { it.isNotBlank() }
                                ?: receipt.userId.take(8)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(name, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    formatInfoDate(receipt.readAt ?: receipt.deliveredAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

private fun formatInfoDate(value: String?): String {
    if (value.isNullOrBlank()) return "Pendiente"
    return try {
        // Safe split: "2023-10-27T10:30:00Z" or "2023-10-27 10:30:00"
        val parts = value.trim().replace("T", " ").split(" ")
        if (parts.size >= 2) {
            val date = parts[0] // "2023-10-27"
            val time = parts[1].substringBefore(".") // "10:30:00"
            val dateShort = date.split("-").let { if (it.size >= 3) "${it[2]}/${it[1]}" else date }
            val timeShort = time.take(5)
            "$dateShort $timeShort"
        } else {
            value.take(16).replace("T", " ")
        }
    } catch (_: Exception) {
        value.take(16).replace("T", " ")
    }
}
