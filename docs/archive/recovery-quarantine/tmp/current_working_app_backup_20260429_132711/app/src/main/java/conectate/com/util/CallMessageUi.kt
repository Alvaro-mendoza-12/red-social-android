package conectate.com.util

enum class CallMessageTone {
    DEFAULT,
    MISSED,
    ERROR,
    CANCELLED
}

data class CallMessageUiModel(
    val title: String,
    val subtitle: String,
    val previewText: String,
    val tone: CallMessageTone
)

fun isCallEventContentType(contentType: String?): Boolean {
    return when (contentType?.trim()?.lowercase()) {
        "call", "call_start" -> true
        else -> false
    }
}

fun buildCallEventPreview(contentType: String, rawContent: String): String? {
    return when (contentType.trim().lowercase()) {
        "call" -> buildCallMessageUiModel(contentType, rawContent)?.previewText
        "call_start" -> rawContent.trim().ifBlank { "Llamada en curso" }
        else -> null
    }
}

fun buildCallMessageUiModel(contentType: String, rawContent: String): CallMessageUiModel? {
    if (!contentType.trim().equals("call", ignoreCase = true)) return null

    val content = rawContent.trim()
    val normalized = content.lowercase()

    return when {
        normalized.contains("error en la llamada") -> CallMessageUiModel(
            title = "Error en la llamada",
            subtitle = "Vuelve a intentarlo.",
            previewText = "Error en la llamada",
            tone = CallMessageTone.ERROR
        )
        normalized.contains("no contestada") -> CallMessageUiModel(
            title = "Llamada",
            subtitle = "Sin respuesta",
            previewText = "Llamada · Sin respuesta",
            tone = CallMessageTone.MISSED
        )
        normalized.contains("rechazada") -> CallMessageUiModel(
            title = "Llamada",
            subtitle = "Rechazada",
            previewText = "Llamada · Rechazada",
            tone = CallMessageTone.ERROR
        )
        normalized.contains("cancelada") -> CallMessageUiModel(
            title = "Llamada",
            subtitle = "Cancelada",
            previewText = "Llamada · Cancelada",
            tone = CallMessageTone.CANCELLED
        )
        normalized.contains("duración") || normalized.contains("duracion") -> {
            val durationRaw = content
                .substringAfter("duración", missingDelimiterValue = content.substringAfter("duracion", ""))
                .trim()
                .trimStart('•', ':', '-', ' ')
            var subtitle = normalizeCallDurationLabel(durationRaw.ifBlank { content })
            CallMessageUiModel(
                title = "Llamada",
                subtitle = subtitle,
                previewText = "Llamada · $subtitle",
                tone = CallMessageTone.DEFAULT
            )
        }
        normalized.contains("completada") -> {
            val trailing = content.substringAfter('•', "").trim()
            var subtitle = if (trailing.isBlank()) "Completada" else normalizeCallDurationLabel(trailing)
            CallMessageUiModel(
                title = "Llamada",
                subtitle = subtitle,
                previewText = "Llamada · $subtitle",
                tone = CallMessageTone.DEFAULT
            )
        }
        normalized.contains("finalizada") -> CallMessageUiModel(
            title = "Llamada",
            subtitle = "Finalizada",
            previewText = "Llamada · Finalizada",
            tone = CallMessageTone.DEFAULT
        )
        else -> {
            var subtitle = content.ifBlank { "Finalizada" }
            CallMessageUiModel(
                title = "Llamada",
                subtitle = subtitle,
                previewText = "Llamada · $subtitle",
                tone = CallMessageTone.DEFAULT
            )
        }
    }
}

private fun normalizeCallDurationLabel(raw: String): String {
    val cleaned = raw
        .trim()
        .trim('•', '-', ':', ' ')
        .ifBlank { return "Completada" }

    var parts = cleaned.split(':').map { it.trim() }
    when (parts.size) {
        2 -> {
            var minutes = parts[0].toIntOrNull()
            var seconds = parts[1].toIntOrNull()
            if (minutes != null && seconds != null) {
                return buildDurationLabel(hours = 0, minutes = minutes, seconds = seconds)
            }
        }
        3 -> {
            var hours = parts[0].toIntOrNull()
            var minutes = parts[1].toIntOrNull()
            var seconds = parts[2].toIntOrNull()
            if (hours != null && minutes != null && seconds != null) {
                return buildDurationLabel(hours = hours, minutes = minutes, seconds = seconds)
            }
        }
    }

    return cleaned
}

private fun buildDurationLabel(hours: Int, minutes: Int, seconds: Int): String {
    var parts = mutableListOf<String>()
    if (hours > 0) parts += "$hours h"
    if (minutes > 0) parts += "$minutes min"
    if (seconds > 0 || parts.isEmpty()) parts += "$seconds s"
    return parts.joinToString(" ")
}



