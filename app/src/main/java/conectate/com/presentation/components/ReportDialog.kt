package conectate.com.presentation.components

import android.net.Uri
import androidx.compose.runtime.Composable

@Composable
fun ReportDialog(
    reportedUserId: String,
    reportedUserName: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (reason: String, description: String, evidenceUri: Uri?) -> Result<Unit>,
) {
    ContentReportDialog(
        title = "Reportar usuario",
        reportKey = "user:$reportedUserId",
        subjectText = "Usuario: $reportedUserName",
        reasonOptions = listOf("Spam", "Acoso", "Estafa", "Contenido inapropiado", "Otro"),
        attachEvidenceEnabled = true,
        inlineSuccessConfirmation = true,
        maxDetailsLength = 1_000,
        onDismiss = onDismiss,
        onSubmit = { reason, description, uri -> onSubmit(reason, description.orEmpty(), uri) },
    )
}
