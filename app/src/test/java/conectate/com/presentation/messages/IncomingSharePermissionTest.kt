package conectate.com.presentation.messages

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import conectate.com.util.PendingOutgoingMediaStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IncomingSharePermissionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication() as Application
    private val uri = Uri.parse("content://audit.share/item/1")
    private fun intent(readFlag: Boolean = true) = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri).apply { if (readFlag) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }

    @Test fun aReadFlagWithoutAnActualGrantCannotQueryOrCopyTheProvidersData() = runBlocking {
        val provider = Robolectric.buildContentProvider(IncomingShareParsingTest.FilesProvider::class.java).create("audit.share").get()
        val denied = IncomingSharePermissionContext(context, emptySet())
        val parsed = parseIncomingShareIntent(denied, intent())!!
        try {
            assertTrue(parsed.items.isEmpty()); assertEquals(1, parsed.failedItemCount)
            assertEquals(0, provider.mimeReads)
            assertTrue(PendingOutgoingMediaStore.persistIncomingShare(denied, parsed).getOrThrow().items.isEmpty())
            assertEquals(Process.myPid(), denied.lastPid)
            assertEquals(Process.myUid(), denied.lastUid)
        } finally { PendingOutgoingMediaStore.deleteIncomingShare(context, parsed) }
    }

    @Test fun anEarlierGrantAloneDoesNotAuthorizeAnotherUnflaggedIncomingIntent() {
        val provider = Robolectric.buildContentProvider(IncomingShareParsingTest.FilesProvider::class.java).create("audit.share").get()
        val granted = IncomingSharePermissionContext(context, setOf(uri))
        val parsed = parseIncomingShareIntent(granted, intent(readFlag = false))!!
        assertTrue(parsed.items.isEmpty())
        assertEquals(1, parsed.failedItemCount)
        assertEquals(0, provider.mimeReads)
    }

    @Test fun revokingTheGrantStopsAReusedIntentBeforeAnyProviderMetadata() {
        val provider = Robolectric.buildContentProvider(IncomingShareParsingTest.FilesProvider::class.java).create("audit.share").get()
        assertEquals(1, parseIncomingShareIntent(IncomingSharePermissionContext(context, setOf(uri)), intent())!!.items.size)
        val reads = provider.mimeReads
        assertTrue(parseIncomingShareIntent(IncomingSharePermissionContext(context, emptySet()), intent())!!.items.isEmpty())
        assertEquals(reads, provider.mimeReads)
    }

    @Test fun grantingAProviderUriCannotAuthorizeAnOwnPrivateProvider() {
        val own = Uri.parse("content://${context.packageName}.provider/private/file")
        assertFalse(mayReadIncomingShareSource(IncomingSharePermissionContext(context, setOf(own)), own, hasReadGrantFlag = true))
    }

    class SharedDocumentProvider : ContentProvider() {
        lateinit var file: File
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(columnNames.map { if (it == OpenableColumns.SIZE) file.length() else file.name })
            }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Test fun anAuthorizedExternalDocumentStillCopiesItsExactBytesAndRestoresPrivately() = runBlocking {
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 900_000, 900_000)
        val file = temporary.newFile("presentation.pptx").apply { writeBytes(ByteArray(1000) { (it % 127).toByte() }) }
        Robolectric.buildContentProvider(SharedDocumentProvider::class.java).create("audit.share").get().file = file
        val parsed = parseIncomingShareIntent(IncomingSharePermissionContext(context, setOf(uri)), intent())!!
        try {
            val captured = PendingOutgoingMediaStore.persistIncomingShare(context, parsed).getOrThrow()
            assertEquals(1, captured.items.size)
            assertArrayEquals(file.readBytes(), File(captured.items.single().uri.path!!).readBytes())
            val restored = parseIncomingShareIntent(context, captured.toCapturedShareIntent(), captured.id)!!
            assertEquals(captured.items, restored.items)
        } finally { PendingOutgoingMediaStore.deleteIncomingShare(context, parsed) }
    }
}
