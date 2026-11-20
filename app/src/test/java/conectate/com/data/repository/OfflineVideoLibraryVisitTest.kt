package conectate.com.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.model.Video
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineVideoLibraryVisitTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val visits = AuthSessionMutationGuard()
    private var account = "library-first"
    private val auth = mockk<AuthService>().also {
        every { it.captureAccountLease() } answers { visits.captureAccountLease { account } }
        every { it.commitForAccountLease(any(), any()) } answers { visits.commitForLease(firstArg(), secondArg()) }
    }

    @Test fun libraryReopensWithItsOwnFilesAndTarget() {
        val file = File(context.filesDir, "offline-videos/$account/local.mp4")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        val entry = OfflineVideoEntry(Video(id = "local"), file.absolutePath, downloadedAtEpochMs = 1, sizeBytes = 3)
        val preferences = context.getSharedPreferences("offline_video_library_v1", Context.MODE_PRIVATE)
        preferences.edit().putString("account:$account", Json.encodeToString(listOf(entry))).commit()
        val library = OfflineVideoRepository(auth, context)
        val first = library.captureOwner()
        library.setConfiguredTargetCount(4, first)
        assertEquals(listOf(entry), library.videos.value)
        visits.invalidate { account = "library-second" }
        val second = library.captureOwner()
        library.refreshForCurrentAccount(second)
        library.setConfiguredTargetCount(9, first)
        library.refreshForCurrentAccount(first)
        assertTrue(library.videos.value.isEmpty())
        assertEquals(0, library.configuredTargetCount(second))
        visits.invalidate { account = "library-first" }
        val returning = library.captureOwner()
        library.refreshForCurrentAccount(returning)
        assertEquals(listOf(entry), library.videos.value)
        assertEquals(4, library.configuredTargetCount(returning))
        assertFalse(first.isCurrent())
    }
}
