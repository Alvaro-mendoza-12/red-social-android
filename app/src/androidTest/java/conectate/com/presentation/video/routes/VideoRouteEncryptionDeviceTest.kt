package conectate.com.presentation.video.routes

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class VideoRouteEncryptionDeviceTest {
    @Test fun realAndroidKeystoreStorageRestoresTheRouteAndHidesItsPrivateTextOnDisk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "route-test-${UUID.randomUUID()}"
        val other = "route-test-${UUID.randomUUID()}"
        val record = VideoRoute(
            "private-test-route", "solo-en-mi-dispositivo-1045", 3,
            listOf(VideoRouteItem("video", "Referencia", "author", "Autor", opened = true)),
            conclusion = "conclusion-privada-8402",
        )
        val journal = VideoRouteJournal(encryptedVideoRoutePreferences(context))
        try {
            journal.replace(account, null, record)
            journal.replace(other, null, record.copy(id = "other-route"))
            val reopened = VideoRouteJournal(encryptedVideoRoutePreferences(context))
            assertEquals(record, reopened.read(account))
            val raw = File(context.applicationInfo.dataDir, "shared_prefs/$VIDEO_ROUTE_PREFERENCES.xml").readText()
            assertFalse(raw.contains(record.topic))
            assertFalse(raw.contains(record.conclusion))
            assertFalse(raw.contains(account))
            journal.erase(account)
            assertNull(reopened.read(account))
            assertNotNull(reopened.read(other))
        } finally {
            journal.erase(account)
            journal.erase(other)
        }
    }
}
