package conectate.com.presentation.messages

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

private const val CALENDAR_TASK_DURATION_MS = 30 * 60 * 1_000L
private const val CALENDAR_TITLE_CODE_POINTS = 120
// Keep corrupt stored timestamps outside the calendar editor (year 9999, UTC).
private const val LAST_CALENDAR_INSTANT_MS = 253_402_300_799_999L

/** Opens a reviewable event draft; it never inserts into the calendar provider directly. */
internal fun workspaceCalendarIntent(entry: ChatWorkspaceEntry): Intent? {
    if (entry.kind != ChatWorkspaceKind.TASK || entry.completed) return null
    val start = entry.dueAt ?: return null
    if (start <= 0 || start > LAST_CALENDAR_INSTANT_MS - CALENDAR_TASK_DURATION_MS) return null
    val description = entry.text.trim().takeIf { it.isNotBlank() } ?: return null
    val singleLineTitle = description.replace(Regex("\\s+"), " ")
    val title = if (singleLineTitle.codePointCount(0, singleLineTitle.length) > CALENDAR_TITLE_CODE_POINTS) {
        singleLineTitle.substring(0, singleLineTitle.offsetByCodePoints(0, CALENDAR_TITLE_CODE_POINTS - 1)) + "…"
    } else {
        singleLineTitle
    }
    return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.Events.TITLE, title)
        putExtra(CalendarContract.Events.DESCRIPTION, description)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + CALENDAR_TASK_DURATION_MS)
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
        putExtra(CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.ACCESS_PRIVATE)
    }
}

/** Null means the editor opened, not that the user saved the event. */
internal fun openWorkspaceCalendar(context: Context, entry: ChatWorkspaceEntry): String? {
    val intent = workspaceCalendarIntent(entry)
        ?: return "Elige un pendiente sin completar, con texto y una fecha válidos."
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        // Starting directly avoids false negatives from Android package visibility filters.
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "No hay una aplicación de calendario disponible para abrir este pendiente."
    } catch (_: SecurityException) {
        "El dispositivo no permite abrir el calendario. Revisa sus restricciones."
    }
}
