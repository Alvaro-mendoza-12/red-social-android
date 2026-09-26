package conectate.com.presentation.messages

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

internal fun workspaceDueLabel(dueAt: Long): String = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.getDefault())
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(dueAt))

@Composable
internal fun WorkspaceScheduleDialog(entry: ChatWorkspaceEntry, onDismiss: () -> Unit, onSchedule: (Long) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(context, entry.id) {
        val selected = Calendar.getInstance().apply { timeInMillis = entry.dueAt ?: System.currentTimeMillis() }
        var timeDialog: TimePickerDialog? = null
        val dateDialog = DatePickerDialog(context, { _, year, month, day ->
            selected.set(Calendar.YEAR, year)
            selected.set(Calendar.MONTH, month)
            selected.set(Calendar.DAY_OF_MONTH, day)
            timeDialog = TimePickerDialog(context, { _, hour, minute ->
                selected.set(Calendar.HOUR_OF_DAY, hour)
                selected.set(Calendar.MINUTE, minute)
                selected.set(Calendar.SECOND, 0)
                selected.set(Calendar.MILLISECOND, 0)
                onSchedule(selected.timeInMillis)
                onDismiss()
            }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).apply {
                setTitle("Hora del pendiente")
                setOnCancelListener { onDismiss() }
                show()
            }
        }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).apply {
            setTitle("Fecha del pendiente")
            setOnCancelListener { onDismiss() }
            show()
        }
        onDispose { dateDialog.dismiss(); timeDialog?.dismiss() }
    }
}
