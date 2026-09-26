package conectate.com.presentation.messages

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.CalendarContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class WorkspaceCalendarExportTest {
    private val task = ChatWorkspaceEntry(
        kind = ChatWorkspaceKind.TASK,
        text = "Preparar la reunión\nLlevar los documentos y revisar el presupuesto.",
        dueAt = 1_800_000_000_000L,
    )

    @Test
    fun eventOpensAnEditorWithFullDescriptionAndThirtyMinuteSlot() {
        val intent = requireNotNull(workspaceCalendarIntent(task))

        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertEquals(task.text, intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals(task.text.replace('\n', ' '), intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(task.dueAt, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        assertEquals(task.dueAt!! + 1_800_000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
        assertFalse(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true))
        assertEquals(CalendarContract.Events.ACCESS_PRIVATE, intent.getIntExtra(CalendarContract.Events.ACCESS_LEVEL, -1))
        assertFalse(intent.hasExtra(Intent.EXTRA_EMAIL))
    }

    @Test
    fun longTitlesPreserveUnicodeAndTheFullTaskRemainsInDescription() {
        val description = "📅".repeat(130) + "\nNo perder este detalle."
        val intent = requireNotNull(workspaceCalendarIntent(task.copy(text = description)))
        val title = requireNotNull(intent.getStringExtra(CalendarContract.Events.TITLE))

        assertEquals(120, title.codePointCount(0, title.length))
        assertEquals("📅".repeat(119) + "…", title)
        assertEquals(description, intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
    }

    @Test
    fun notesCompletedTasksBlankTasksAndUnscheduledTasksDoNotExport() {
        assertNull(workspaceCalendarIntent(task.copy(kind = ChatWorkspaceKind.CONTEXT)))
        assertNull(workspaceCalendarIntent(task.copy(kind = ChatWorkspaceKind.DRAFT)))
        assertNull(workspaceCalendarIntent(task.copy(completed = true)))
        assertNull(workspaceCalendarIntent(task.copy(text = " \n\t")))
        assertNull(workspaceCalendarIntent(task.copy(dueAt = null)))
    }

    @Test
    fun invalidAndOverflowingDatesAreRejectedButPastTasksCanBeRescheduledByTheUser() {
        listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE - 1_800_000L, Long.MAX_VALUE).forEach { date ->
            assertNull("Invalid date $date", workspaceCalendarIntent(task.copy(dueAt = date)))
        }
        assertNotNull(workspaceCalendarIntent(task.copy(dueAt = 1L)))
        val latestStart = 253_402_300_799_999L - 1_800_000L
        val intent = requireNotNull(workspaceCalendarIntent(task.copy(dueAt = latestStart)))
        assertEquals(253_402_300_799_999L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
        assertNull(workspaceCalendarIntent(task.copy(dueAt = latestStart + 1)))
    }

    @Test
    fun launchingFromAnApplicationContextOnlyOpensTheDraft() {
        val context = RecordingContext()

        assertNull(openWorkspaceCalendar(context, task))

        val intent = requireNotNull(context.startedIntent)
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(task.completed)
    }

    @Test
    fun missingOrRestrictedCalendarShowsAnErrorWithoutCrashing() {
        val missingCalendar = RecordingContext(ActivityNotFoundException())
        val restrictedCalendar = RecordingContext(SecurityException())

        assertTrue(openWorkspaceCalendar(missingCalendar, task)!!.contains("No hay"))
        assertTrue(openWorkspaceCalendar(restrictedCalendar, task)!!.contains("restricciones"))
    }

    @Test
    fun invalidTasksDoNotAttemptToLaunchAnActivity() {
        val context = RecordingContext()

        assertNotNull(openWorkspaceCalendar(context, task.copy(dueAt = null)))
        assertNull(context.startedIntent)
    }

    private class RecordingContext(private val failure: RuntimeException? = null) :
        ContextWrapper(RuntimeEnvironment.getApplication()) {
        var startedIntent: Intent? = null

        override fun startActivity(intent: Intent) {
            failure?.let { throw it }
            startedIntent = intent
        }
    }
}
