package conectate.com.presentation.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


fun ForcedSuspensionAlertDialog(
    visible: Boolean,
    suspensionEndDate: String?,
    reason: String?,
    onAccept: () -> Unit
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Cuenta suspendida") },
        text = {
            val untilText = formatSuspensionDate(suspensionEndDate)
            val reasonText = reason?.trim().orEmpty()
            val permanent = suspensionEndDate.isNullOrBlank()
            val message = buildString {
                append("Tu cuenta ha sido suspendida por incumplir las normas de la comunidad o políticas de privacidad.\n")
                if (reasonText.isNotBlank()) {
                    append("\nMotivo: $reasonText\n")
                }
                append(
                    if (permanent) {
                        "\nLa suspensión es permanente."
                    } else {
                        "\nSuspendida hasta: $untilText"
                    }
                )
            }
            Text(message)
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("Aceptar")
            }
        }
    )
}

private fun formatSuspensionDate(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return "nuevo aviso"
    return runCatching {
        val normalized = if (value.contains("T")) value else value.replace(" ", "T")
        val instant = Instant.parse(
            if (normalized.endsWith("Z") || normalized.contains("+")) normalized else "${normalized}Z"
        )
        instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    }.getOrDefault(value)
}






