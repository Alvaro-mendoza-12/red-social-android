package conectate.com.presentation.common

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import conectate.com.BuildConfig
import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.SystemAnnouncement
import conectate.com.data.localfallback.SystemAnnouncementEnvelope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SystemAnnouncementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = dispatcher.scheduler
    private val store = ViewModelStore()
    private val api = mockk<LocalFallbackApiClient>()
    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxUnitFun = true)
    private val savedSeen = mutableMapOf<String, Set<String>>()
    private var cache = "{}"
    private val nextVersion = BuildConfig.VERSION_CODE + 1
    private lateinit var model: SystemAnnouncementViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { context.getSharedPreferences("system_announcements", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString("cache", "{}") } answers { cache }
        every { prefs.getStringSet(any(), any()) } answers { savedSeen[firstArg()] ?: emptySet() }
        every { prefs.edit() } returns editor
        every { editor.putString("cache", any()) } answers { cache = secondArg(); editor }
        every { editor.putStringSet(any(), any()) } answers { savedSeen[firstArg()] = secondArg(); editor }
        model = SystemAnnouncementViewModel(context, api, object : DispatcherProvider {
            override val io = dispatcher
            override val default = dispatcher
            override val main = dispatcher
            override val mainImmediate = dispatcher
        })
        store.put("announcements", model)
    }

    @After fun cleanup() {
        store.clear()
        clock.runCurrent()
        Dispatchers.resetMain()
    }

    private fun info(id: String) = SystemAnnouncement(id, "info", "Novedades", "Contenido", "2100-01-01T00:00:00Z")

    private fun update(version: Int) = info("update-$version").copy(kind = "update", versionCode = version)

    @Test fun aStoreNoticeWithoutAPlayOfferDoesNotPollInTheBackground() {
        coEvery { api.loadSystemAnnouncements(null) } returns Result.success(listOf(update(nextVersion)))

        model.watchVisibleNotices(null, null)
        clock.advanceTimeBy(45_001)

        assertEquals(emptyList<SystemAnnouncement>(), model.state.value.notices)
        coVerify(exactly = 1) { api.loadSystemAnnouncements(null) }
    }

    @Test fun aDifferentStoreVersionAndHiddenNewsDoNotPollDuringThePlayDialog() {
        coEvery { api.loadSystemAnnouncements(null) } returns Result.success(listOf(info("news"), update(nextVersion + 1)))

        model.watchVisibleNotices("owner", nextVersion)
        clock.advanceTimeBy(45_001)

        assertEquals(emptyList<SystemAnnouncement>(), model.state.value.notices)
        coVerify(exactly = 1) { api.loadSystemAnnouncements(null) }
    }

    @Test fun matchingUpdateNotesAreRefreshedAndStopPollingWhenRevoked() {
        val matching = update(nextVersion)
        val hidden = listOf(info("news"), update(nextVersion + 1))
        coEvery { api.loadSystemAnnouncements(null) } returnsMany listOf(
            Result.success(hidden + matching),
            Result.success(hidden),
        )

        model.watchVisibleNotices("owner", nextVersion)
        clock.runCurrent()
        assertEquals(listOf(matching), model.state.value.notices)

        clock.advanceTimeBy(45_001)
        assertEquals(emptyList<SystemAnnouncement>(), model.state.value.notices)
        coVerify(exactly = 2) { api.loadSystemAnnouncements(null) }
    }

    @Test fun closingTheLastNewsItemStopsPollingAndPersistsOnlyForItsAccount() {
        val notice = info("news")
        coEvery { api.loadSystemAnnouncements(null) } returns Result.success(listOf(notice))

        val first = model.watchVisibleNotices("account-a", null)
        clock.runCurrent()
        model.dismiss(first, notice.id)
        clock.advanceTimeBy(45_001)
        coVerify(exactly = 1) { api.loadSystemAnnouncements(null) }
        assertEquals(mapOf("seen:account-a" to setOf(notice.id)), savedSeen)

        model.watchVisibleNotices("account-a", null)
        clock.runCurrent()
        assertEquals(emptyList<SystemAnnouncement>(), model.state.value.notices)
        model.watchVisibleNotices("account-b", null)
        clock.runCurrent()
        assertEquals(listOf(notice), model.state.value.notices)
    }

    @Test fun aRetainedCloseOrPauseCannotChangeANewerVisitToTheSameAccount() {
        val notice = info("news")
        coEvery { api.loadSystemAnnouncements(null) } returns Result.success(listOf(notice))

        val old = model.watchVisibleNotices("account-a", null)
        clock.runCurrent()
        model.watchVisibleNotices("account-b", null)
        val current = model.watchVisibleNotices("account-a", null)
        clock.runCurrent()

        model.stopWatching(old)
        model.dismiss(old, notice.id)
        clock.runCurrent()
        assertSame(current, model.state.value.visit)
        assertEquals(listOf(notice), model.state.value.notices)
        assertEquals(emptyMap<String, Set<String>>(), savedSeen)

        model.stopWatching(current)
        assertNull(model.state.value.visit)
        assertEquals(emptyList<SystemAnnouncement>(), model.state.value.notices)
    }

    @Test fun aLateResponseCannotRestoreOldNoticesOrOverwriteTheCache() {
        val oldResponse = CompletableDeferred<Result<List<SystemAnnouncement>>>()
        val fresh = info("fresh")
        var requests = 0
        coEvery { api.loadSystemAnnouncements(null) } coAnswers {
            if (++requests == 1) withContext(NonCancellable) { oldResponse.await() }
            else Result.success(listOf(fresh))
        }

        try {
            model.watchVisibleNotices("account-a", null)
            clock.runCurrent()
            model.watchVisibleNotices("account-b", null)
            model.watchVisibleNotices("account-a", null)
            clock.runCurrent()
        } finally {
            oldResponse.complete(Result.success(listOf(info("old"))))
            clock.runCurrent()
        }

        assertEquals(listOf(fresh), model.state.value.notices)
        assertEquals(listOf(fresh), Json.decodeFromString<SystemAnnouncementEnvelope>(cache).items)
    }

    @Test fun aFailedRefreshStillDisplaysCachedNewsAndStopsWhenPaused() {
        val cachedNotice = info("cached")
        cache = Json.encodeToString(SystemAnnouncementEnvelope.serializer(), SystemAnnouncementEnvelope(listOf(cachedNotice)))
        coEvery { api.loadSystemAnnouncements(null) } returns Result.failure(IllegalStateException("offline"))

        val visit = model.watchVisibleNotices(null, null)
        clock.runCurrent()
        assertEquals(listOf(cachedNotice), model.state.value.notices)
        clock.advanceTimeBy(15_001)
        model.stopWatching(visit)
        clock.advanceTimeBy(45_001)

        assertEquals(emptyList<SystemAnnouncement>(), model.state.value.notices)
        coVerify(exactly = 2) { api.loadSystemAnnouncements(null) }
    }
}
