package conectate.com.data.local.prefs

import android.content.Context
import android.content.SharedPreferences
import conectate.com.data.webrtc.LiveCameraFilter
import conectate.com.data.model.LiveCreatorLayoutStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

private const val LIVE_PREFERENCES_NAME = "live_preferences"

/**
 * Device-local preferences that affect the live capture experience.
 *
 * The selected camera look is intentionally local to the broadcaster's device:
 * it is a capture preference, not live metadata that should be trusted by the
 * server or sent to viewers.
 */
@Singleton
class LivePreferences @Inject constructor(
    private val storage: LivePreferenceStorage
) {
    private companion object {
        private const val KEY_CAMERA_FILTER = "camera_filter"
        private const val KEY_CREATOR_LAYOUT = "creator_layout"
    }

    val cameraFilter: LiveCameraFilter
        get() = LiveCameraFilter.fromWireValue(storage.getString(KEY_CAMERA_FILTER))

    fun setCameraFilter(filter: LiveCameraFilter) {
        storage.putString(KEY_CAMERA_FILTER, filter.wireValue)
    }

    val creatorLayoutStyle: LiveCreatorLayoutStyle
        get() = LiveCreatorLayoutStyle.fromWireValue(storage.getString(KEY_CREATOR_LAYOUT))

    fun setCreatorLayoutStyle(style: LiveCreatorLayoutStyle) {
        storage.putString(KEY_CREATOR_LAYOUT, style.wireValue)
    }
}

interface LivePreferenceStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class SharedPreferencesLivePreferenceStorage(
    private val preferences: SharedPreferences
) : LivePreferenceStorage {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LivePreferencesModule {
    @Provides
    @Singleton
    fun provideLivePreferenceStorage(
        @ApplicationContext context: Context
    ): LivePreferenceStorage = SharedPreferencesLivePreferenceStorage(
        context.getSharedPreferences(LIVE_PREFERENCES_NAME, Context.MODE_PRIVATE)
    )
}
