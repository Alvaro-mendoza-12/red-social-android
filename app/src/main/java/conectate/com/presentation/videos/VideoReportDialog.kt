package conectate.com.presentation.videos

import androidx.compose.runtime.Composable
import conectate.com.presentation.components.ContentReportDialog

@Composable
fun VideoReportDialog(
    videoId: String,
    alreadyReported: Boolean,
    onDismiss: () -> Unit,
    onSubmit: suspend (reason: String, details: String?) -> Result<Unit>,
) {
    ContentReportDialog(
        title = "Reportar video",
        reportKey = "video:$videoId",
        alreadyReported = alreadyReported,
        alreadyReportedTitle = "Video reportado",
        alreadyReportedMessage = "Ya enviaste un reporte para este video.",
        reasonOptions = listOf("Contenido inapropiado", "Acoso o bullying", "Spam o estafa",
            "Violencia explícita", "Desinformación", "Otro"),
        onDismiss = onDismiss,
        onSubmit = { reason, details, _ -> onSubmit(reason, details) },
    )
}
