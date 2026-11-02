package conectate.com.presentation.messages

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import conectate.com.util.IncomingSharePayload
import conectate.com.util.IncomingSharedItem
import conectate.com.util.MAX_INCOMING_SHARE_ITEMS
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IncomingShareParsingTest {
    class FilesProvider : ContentProvider() {
        var mimeReads = 0
        override fun onCreate() = true
        override fun getType(uri: Uri): String { mimeReads++; return "application/octet-stream" }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(columnNames.map { if (it == OpenableColumns.SIZE) 100L else "file-${uri.lastPathSegment}.bin" })
            }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    private val context get() = RuntimeEnvironment.getApplication() as Application

    @Test fun excessiveSelectionsAreCountedWithoutQueryingEveryProvidersMetadata() {
        val provider = Robolectric.buildContentProvider(FilesProvider::class.java).create("audit.share").get()
        val files = ArrayList((0 until 50).map { Uri.parse("content://audit.share/item/$it") })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, files).setType("*/*")
        val result = parseIncomingShareIntent(context, intent)!!
        assertEquals(MAX_INCOMING_SHARE_ITEMS, result.items.size)
        assertEquals(50 - MAX_INCOMING_SHARE_ITEMS, result.failedItemCount)
        assertEquals(MAX_INCOMING_SHARE_ITEMS, provider.mimeReads)
    }

    @Test fun aSubjectOnlyShareStillContainsUsableText() {
        val result = parseIncomingShareIntent(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, "Read this"))!!
        assertEquals("Read this", result.subject)
        assertTrue(result.hasContent); assertTrue(result.isTextOnly)
    }

    @Test fun restoringACapturedIntentPreservesItsIdentityAndDocumentMetadata() {
        val original = IncomingSharePayload(items = listOf(IncomingSharedItem(Uri.parse("file:///sample.pptx"),
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", "sample.pptx", 10_000)))
        val result = parseIncomingShareIntent(context, original.toCapturedShareIntent(), original.id)!!
        assertEquals(original.id, result.id)
        assertEquals(original.items, result.items)
        assertEquals(original.id, capturedIncomingShareId(original.toCapturedShareIntent()))
    }

    @Test fun aFreshShareDoesNotInheritAPreviousCaptureIdentity() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "same text")
        assertNotEquals(parseIncomingShareIntent(context, intent)?.id, parseIncomingShareIntent(context, intent)?.id)
    }

    @Test fun anOverflowingFailedCountCannotWrapIntoANegativeOrSuccessfulCount() {
        Robolectric.buildContentProvider(FilesProvider::class.java).create("audit.share").get()
        val files = ArrayList((0 until 25).map { Uri.parse("content://audit.share/item/$it") })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, files)
            .putExtra("conectate.capture.failed_count", Int.MAX_VALUE)
        val id = java.util.UUID.randomUUID().toString()
        intent.putExtra("conectate.capture.share_id", id)
        assertEquals(Int.MAX_VALUE, parseIncomingShareIntent(context, intent, id)?.failedItemCount)
    }
}
