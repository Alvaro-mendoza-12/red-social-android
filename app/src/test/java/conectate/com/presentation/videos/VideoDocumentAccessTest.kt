package conectate.com.presentation.videos

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VideoDocumentAccessTest {
    @Test fun chosenDocumentRetainsReadOnlyAccess() {
        val uri=Uri.parse("content://documents/selected-video")
        var requestedFlags=0
        assertTrue(retainVideoDocumentAccess(uri) { chosen, flags -> assertEquals(uri,chosen);requestedFlags=flags })
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,requestedFlags)
    }
    @Test fun unsupportedOrRevokedGrantReturnsAnActionableFailure() {
        val uri=Uri.parse("content://documents/revoked")
        assertFalse(retainVideoDocumentAccess(uri) { _,_ -> throw SecurityException("revoked") })
        assertFalse(retainVideoDocumentAccess(uri) { _,_ -> throw IllegalArgumentException("unsupported") })
    }
    @Test fun localFilesNeedNoGrantAndExternalLinksCannotReceiveOne() {
        assertTrue(retainVideoDocumentAccess(Uri.parse("file:///local/source.mp4")) { _,_ -> fail("No grant needed") })
        assertFalse(retainVideoDocumentAccess(Uri.parse("https://example.invalid/video")) { _,_ -> fail("No remote grant") })
    }
}
