package conectate.com.presentation.messages

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import conectate.com.util.IncomingSharePayload
import conectate.com.util.IncomingSharedItem
import conectate.com.util.PendingOutgoingMediaStore
import java.io.File
import java.util.UUID
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IncomingShareSourcePolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication() as Application
    private fun share(uri: Uri) = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    @Test fun anotherAppsFileUriCannotReadOrCopyThisAppsPrivateData() = runBlocking {
        val file = File(context.filesDir, "private-${UUID.randomUUID()}.txt").apply { writeText("private session material") }
        val parsed = parseIncomingShareIntent(context, share(Uri.fromFile(file)))!!
        try {
            assertTrue(parsed.items.isEmpty())
            assertEquals(1, parsed.failedItemCount)
            val captured = PendingOutgoingMediaStore.persistIncomingShare(context, parsed).getOrThrow()
            assertTrue(captured.items.isEmpty())
            assertEquals("private session material", file.readText())
        } finally { file.delete(); PendingOutgoingMediaStore.deleteIncomingShare(context, parsed) }
    }

    @Test fun anOwnProviderUriIsRejectedBeforeItsMetadataIsQueried() {
        val authority = "${context.packageName}.provider"
        val provider = Robolectric.buildContentProvider(IncomingShareParsingTest.FilesProvider::class.java).create(authority).get()
        for (prefix in listOf(authority, "0@$authority")) {
            val parsed = parseIncomingShareIntent(context, share(Uri.parse("content://$prefix/private/file")))!!
            assertTrue(parsed.items.isEmpty())
            assertEquals(1, parsed.failedItemCount)
        }
        assertEquals(0, provider.mimeReads)
    }

    @Test fun aKnownTaskMayRestoreOnlyItsOwnCapturedDirectory() {
        val id = UUID.randomUUID().toString()
        val uri = Uri.fromFile(File(context.filesDir, "pending-incoming-share/$id/file.pptx"))
        val payload = IncomingSharePayload(id = id, items = listOf(IncomingSharedItem(uri,
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", "slides.pptx", 123)))
        assertTrue(parseIncomingShareIntent(context, payload.toCapturedShareIntent())!!.items.isEmpty())
        val restored = parseIncomingShareIntent(context, payload.toCapturedShareIntent(), id)!!
        assertEquals(id, restored.id)
        assertEquals(payload.items, restored.items)
        val other = payload.copy(items = listOf(payload.items.single().copy(uri = Uri.fromFile(File(context.filesDir, "pending-incoming-share/other/file.pptx")))))
        assertTrue(parseIncomingShareIntent(context, other.toCapturedShareIntent(), id)!!.items.isEmpty())
    }

    @Test fun aRestoredCaptureCannotTraverseOutOfItsDirectory() {
        val id = UUID.randomUUID().toString()
        val uri = Uri.fromFile(File(context.filesDir, "pending-incoming-share/$id/../../secret.txt"))
        assertFalse(mayReadIncomingShareSource(context, uri, id))
    }

    @Test fun appOwnedFilesOnExternalStorageAreAlsoPrivateSources() {
        val roots = context.getExternalFilesDirs(null).filterNotNull() + context.externalCacheDirs.filterNotNull()
        assertTrue(roots.isNotEmpty())
        for (root in roots) assertFalse(mayReadIncomingShareSource(context, Uri.fromFile(File(root, "private-file.txt"))))
    }

    @Test fun forgedCaptureExtrasDoNotFixTheIdOrOverrideProviderMetadata() {
        Robolectric.buildContentProvider(IncomingShareParsingTest.FilesProvider::class.java).create("audit.share").get()
        val original = IncomingSharePayload(items = listOf(IncomingSharedItem(Uri.parse("content://audit.share/item/1"),
            "application/pdf", "forged.pdf", 999_999)), failedItemCount = Int.MAX_VALUE, captureError = "forged error")
        val granted = IncomingSharePermissionContext(context, original.items.map { it.uri }.toSet())
        val parsed = parseIncomingShareIntent(granted, original.toCapturedShareIntent()
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))!!
        assertNotEquals(original.id, parsed.id)
        assertEquals("file-1.bin", parsed.items.single().displayName)
        assertNotEquals(999_999L, parsed.items.single().sizeBytes)
        assertEquals(0, parsed.failedItemCount)
        assertNull(parsed.captureError)
    }

    @Test fun malformedOrTruncatedCaptureIdsCannotAliasADurableFolder() {
        for (id in listOf("../private", "a".repeat(101), "1-1-1-1-1", "not-a-uuid", "")) {
            assertNull(capturedIncomingShareId(Intent(Intent.ACTION_SEND).putExtra("conectate.capture.share_id", id)))
        }
    }

    @Test fun unreadableCaptureExtrasDoNotCrashTheEarlyActivityRestoreCheck() {
        val broken = object : Intent() {
            override fun getStringExtra(name: String?): String? = throw BadParcelableException("corrupt parcel")
        }
        assertNull(capturedIncomingShareId(broken))
    }

    @Test fun aRawExternalFilePathCannotBorrowTheApplicationsGeneralStoragePermissions() = runBlocking {
        val source = temporary.newFile("presentation.pptx").apply { writeBytes(ByteArray(1000) { (it % 127).toByte() }) }
        val parsed = parseIncomingShareIntent(context, share(Uri.fromFile(source)))!!
        try {
            assertTrue(parsed.items.isEmpty())
            assertEquals(1, parsed.failedItemCount)
            val captured = PendingOutgoingMediaStore.persistIncomingShare(context, parsed).getOrThrow()
            assertTrue(captured.items.isEmpty())
            assertEquals(1000, source.readBytes().size)
        } finally { PendingOutgoingMediaStore.deleteIncomingShare(context, parsed) }
    }

    @Test fun systemDescriptorsAndUnsupportedSchemesCannotBecomeAttachments() {
        for (value in listOf("file:///proc/self/fd/1", "file:///dev/zero", "file:///data/user/0/other/secret",
            "https://example.test/file", "android.resource://other/raw/file", "file://host/private/file")) {
            val parsed = parseIncomingShareIntent(context, share(Uri.parse(value)))!!
            assertTrue(value, parsed.items.isEmpty())
            assertEquals(value, 1, parsed.failedItemCount)
        }
    }
}
