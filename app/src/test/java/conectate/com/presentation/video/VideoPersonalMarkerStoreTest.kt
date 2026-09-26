package conectate.com.presentation.video

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class VideoPersonalMarkerStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearMarkers() {
        context.getSharedPreferences("video_personal_markers", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun notesPersistAcrossReopeningAndRemainIsolatedByAccount() {
        VideoPersonalMarkerStore(context, " ALICE ").save("video", 7_250L, "El paso clave")
        assertEquals(VideoPersonalMarker(7_250L, "El paso clave"), VideoPersonalMarkerStore(context, "alice").read("video"))
        assertNull(VideoPersonalMarkerStore(context, "bob").read("video"))
        assertNull(VideoPersonalMarkerStore(context, null).save("video", 10, "sin cuenta"))
        assertNull(VideoPersonalMarkerStore(context, null).read("video"))
    }

    @Test fun retentionIsBoundedAndDoesNotDeleteOtherAccountsNotes() {
        val alice = VideoPersonalMarkerStore(context, "alice")
        val bob = VideoPersonalMarkerStore(context, "bob")
        bob.save("video", 100, "conservar")
        repeat(205) { alice.save("video-$it", it * 1_000L, "nota") }
        val keys = context.getSharedPreferences("video_personal_markers", Context.MODE_PRIVATE).all.keys
        assertEquals(200, keys.count { it.startsWith("alice|") })
        assertNotNull(alice.read("video-204"))
        assertEquals("conservar", bob.read("video")?.note)
    }

    @Test fun deletingAndReplacingAMarkerDoesNotLeaveStaleNoteText() {
        val store = VideoPersonalMarkerStore(context, "alice")
        store.save("video", 1_000, "anterior")
        store.save("video", -1, "x".repeat(300))
        assertEquals(0L, store.read("video")?.positionMs)
        assertEquals(240, store.read("video")?.note?.length)
        store.remove("video")
        assertNull(store.read("video"))
    }

    @Test fun multiplePointsCanBeEditedAndDeletedIndependentlyWithoutLosingTheOthers() {
        val store = VideoPersonalMarkerStore(context, "alice")
        store.save("video", 8_000, "segundo")
        store.save("video", 2_000, "primero")
        assertEquals(listOf(2_000L, 8_000L), store.readAll("video").map { it.positionMs })

        assertTrue(store.updateNote("video", 2_000, "inicio"))
        assertTrue(store.remove("video", 8_000))
        assertEquals(listOf(VideoPersonalMarker(2_000, "inicio")), store.readAll("video"))

        repeat(VideoPersonalMarkerStore.MAX_MARKERS_PER_VIDEO - 1) { index ->
            assertNotNull(store.save("video", 10_000L + index, "punto"))
        }
        assertNull(store.save("video", 99_000, "excede límite"))
        assertEquals(VideoPersonalMarkerStore.MAX_MARKERS_PER_VIDEO, store.readAll("video").size)
        assertEquals("inicio", store.readAll("video").first().note)
    }
}
