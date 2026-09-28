package conectate.com.data.local.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeenFriendRequestsPreferencesTest {
    private lateinit var preferences: SeenFriendRequestsPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("seen_friend_requests", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = SeenFriendRequestsPreferences(context)
    }

    @Test
    fun enteringLoadedRequestsClearsOnlyThatAccountsPendingBadge() {
        val ids = listOf("first", "second")
        assertEquals(2, preferences.unseenCount("account-a", ids))
        preferences.markSeen("account-a", ids)
        assertEquals(0, preferences.unseenCount("account-a", ids))
        assertEquals(2, preferences.unseenCount("account-b", ids))
        assertEquals(1, preferences.unseenCount("account-a", ids + "third"))
    }

    @Test
    fun confirmedRowsAreNeverPassedAsPendingAndOldIdsArePruned() {
        preferences.markSeen("account-a", listOf("old"))
        val before = preferences.revision.value
        preferences.markSeen("account-a", emptyList())
        assertEquals(before + 1, preferences.revision.value)
        assertEquals(1, preferences.unseenCount("account-a", listOf("old")))
        assertEquals(0, preferences.unseenCount("account-a", emptyList()))
    }
}
