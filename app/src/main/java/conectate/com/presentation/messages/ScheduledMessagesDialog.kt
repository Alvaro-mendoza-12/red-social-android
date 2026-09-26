package conectate.com.presentation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import conectate.com.data.model.ScheduledMessageJob
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ScheduledMessagesDialog(
    state: ScheduledMessagesUiState,
    onRetry: () -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mensajes programados") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.legacyJobsNeedOwner) {
                    item("legacy") {
                        Text(
                            "Hay mensajes programados antiguos cuya cuenta no se pudo identificar. Se conservan, pero no se enviarán automáticamente.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (state.isLoading) {
                    item("loading") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Actualizando…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.error?.let { error ->
                    item("error") {
                        Column {
                            Text(error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry, enabled = !state.isLoading) { Text("Reintentar") }
                        }
                    }
                }
                if (state.jobs.isEmpty() && !state.isLoading) {
                    item("empty") {
                        Text(
                            if (state.error != null || !state.hasRemoteSnapshot)
                                "No hay mensajes programados guardados en este dispositivo. La lista del servidor aún no se ha confirmado."
                            else "No hay mensajes programados en este chat."
                        )
                    }
                }
                items(state.jobs, key = { "job:${it.id}" }) { job ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            formatScheduledJobTitle(job),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            formatScheduledJobTime(job.scheduled_for),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (job.content.isNotBlank()) {
                            Text(job.content.take(180), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (job.status in setOf("pending", "failed")) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onCancel(job.id) }, enabled = job.id !in state.cancellingIds) {
                                    Text(
                                        if (job.id in state.cancellingIds) "Cancelando…" else "Cancelar",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = {
            if (state.error == null) {
                TextButton(onClick = onRetry, enabled = !state.isLoading) { Text("Actualizar") }
            }
        },
    )
}

private fun formatScheduledJobTitle(job: ScheduledMessageJob): String {
    val type = when (job.content_type) {
        "image" -> "Imagen"
        "video" -> "Video"
        "audio" -> "Audio"
        else -> "Texto"
    }
    val status = when (job.status) {
        "failed" -> "fallido"
        "processing" -> "en envío"
        else -> "pendiente"
    }
    return "$type $status"
}

private fun formatScheduledJobTime(value: String): String = runCatching {
    val time = Instant.parse(value).atZone(ZoneId.systemDefault())
    "Para ${time.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))}"
}.getOrElse { "Para ${value.take(16).replace('T', ' ')}" }
