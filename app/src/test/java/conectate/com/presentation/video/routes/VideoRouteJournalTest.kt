package conectate.com.presentation.video.routes

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import conectate.com.data.model.Video
import java.io.IOException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class VideoRouteJournalTest {
    private lateinit var preferences: SharedPreferences
    private val original = prepareVideoRoute("guitarra", 3, listOf(Video(
        id = "video", userId = "author", title = "Acordes", videoUrl = "https://example.test/video.mp4",
    )))!!

    @Before fun setUp() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("route-journal-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @Test fun aNewJournalRestoresTheSameRecordOnlyForItsNormalizedAccount() {
        VideoRouteJournal(preferences).replace(" ALICE ", null, original)
        assertEquals(original, VideoRouteJournal(preferences).read("alice"))
        assertNull(VideoRouteJournal(preferences).read("bob"))
        assertFalse(preferences.all.keys.single().contains("alice"))
    }

    @Test fun staleAcknowledgementsCannotReplaceAnUpdatedRoute() {
        val journal = VideoRouteJournal(preferences)
        journal.replace("alice", null, original)
        val changed = original.open("video").copy(revision = 1)
        journal.replace("alice", original, changed)
        assertThrows(IllegalStateException::class.java) {
            journal.replace("alice", original, original.copy(conclusion = "late", revision = 1))
        }
        assertEquals(changed, journal.read("alice"))
    }

    @Test fun anotherScreenCannotReplaceOrResurrectANewerSelection() {
        val journal = VideoRouteJournal(preferences)
        journal.replace("alice", null, original)
        val replacement = original.copy(id = "new-route")
        journal.replace("alice", original, replacement)
        assertThrows(IllegalStateException::class.java) { journal.replace("alice", original, null) }
        assertThrows(IllegalStateException::class.java) { journal.replace("alice", null, original) }
        assertEquals(replacement, journal.read("alice"))
    }

    @Test fun aFailedCommitRestoresThePriorInMemoryRecordAndReportsFailure() {
        VideoRouteJournal(preferences).replace("alice", null, original)
        val failing = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        assertThrows(IOException::class.java) {
            VideoRouteJournal(failing).replace("alice", original, original.copy(revision = 1, conclusion = "not acknowledged"))
        }
        assertEquals(original, VideoRouteJournal(preferences).read("alice"))
    }

    @Test fun unreadableDataIsPreservedUntilAnExplicitErase() {
        val journal = VideoRouteJournal(preferences)
        journal.replace("alice", null, original)
        val key = preferences.all.keys.single()
        preferences.edit().putString(key, "{corrupt").commit()
        assertThrows(Exception::class.java) { journal.read("alice") }
        assertThrows(Exception::class.java) { journal.replace("alice", null, original) }
        assertEquals("{corrupt", preferences.getString(key, null))
        journal.erase("alice")
        assertNull(journal.read("alice"))
    }

    @Test fun deletingOneAccountPreservesTheOtherAccountsSelection() {
        val journal = VideoRouteJournal(preferences)
        journal.replace("alice", null, original)
        journal.replace("bob", null, original.copy(id = "bob-route"))
        journal.erase("ALICE")
        assertNull(journal.read("alice"))
        assertEquals("bob-route", journal.read("bob")?.id)
    }

    @Test fun editingTheSameRouteRequiresTheNextRevision() {
        val journal = VideoRouteJournal(preferences)
        journal.replace("alice", null, original)
        assertThrows(IllegalArgumentException::class.java) { journal.replace("alice", original, original) }
        assertThrows(IllegalArgumentException::class.java) { journal.replace("alice", original, original.copy(revision = 2)) }
        assertEquals(original, journal.read("alice"))
    }
}
