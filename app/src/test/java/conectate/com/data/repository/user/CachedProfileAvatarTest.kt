package conectate.com.data.repository.user

import android.app.Application
import android.content.Context
import conectate.com.data.model.User
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CachedProfileAvatarTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = UserLocalStateStore(context)
    private val user = User(id = "owner", avatar_url = "https://example.test/avatar.gif")

    @After fun clear() { store.clearAll() }

    @Test fun cachedAvatarIsReadyBeforeRepositoryInitializationAndIsBoundToItsAccount() {
        store.saveProfile(user)
        assertEquals(user.avatar_url, store.cachedAvatarForUser(" OWNER "))
        assertNull(store.cachedAvatarForUser("other"))
        assertNull(store.cachedAvatarForUser(null))
    }

    @Test fun olderInstallationsRecoverOnlyTheirOwnPhotoFromTheSavedProfile() {
        val prefs = context.getSharedPreferences("user_cache", Context.MODE_PRIVATE)
        prefs.edit().putString("cached_profile", Json.encodeToString(user)).commit()
        assertEquals(user.avatar_url, store.cachedAvatarForUser("owner"))
        assertNull(store.cachedAvatarForUser("other"))
    }

    @Test fun removingAnAvatarOrSigningOutCannotResurrectTheOldPhoto() {
        store.saveProfile(user)
        store.saveProfile(user.copy(avatar_url = null))
        assertNull(store.cachedAvatarForUser("owner"))
        store.saveProfile(user)
        store.clearAll()
        assertNull(store.cachedAvatarForUser("owner"))
    }

    @Test fun compactAvatarRemainsAvailableWhenAnUnrelatedFullProfileSnapshotIsCorrupt() {
        store.saveProfile(user)
        context.getSharedPreferences("user_cache", Context.MODE_PRIVATE).edit()
            .putString("cached_profile", "broken").commit()
        assertEquals(user.avatar_url, store.cachedAvatarForUser("owner"))
        assertNull(store.cachedAvatarForUser("other"))
    }
}
