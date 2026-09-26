package conectate.com.data.local.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class AppSettingsPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "app_settings_preferences"
        private const val KEY_MESSAGE_NOTIFICATIONS = "message_notifications_enabled"
        private const val KEY_CALL_NOTIFICATIONS = "call_notifications_enabled"
        private const val KEY_SOCIAL_NOTIFICATIONS = "social_notifications_enabled"
        private const val KEY_LIVE_NOTIFICATIONS = "live_notifications_enabled"
        private const val KEY_LIVES_IN_VIDEO_FEED = "lives_in_video_feed_enabled"
        private const val KEY_LIVES_IN_SOCIAL = "lives_in_social_enabled"
        private const val KEY_DATA_SAVER = "data_saver_enabled"
        private const val KEY_VIDEO_AUTOPLAY_MOBILE = "video_autoplay_mobile_enabled"
        private const val KEY_AUTO_DOWNLOAD_WIFI = "auto_download_wifi"
        private const val KEY_AUTO_DOWNLOAD_MOBILE = "auto_download_mobile"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_REDUCE_MOTION = "reduce_motion_enabled"
        private const val KEY_BOTTOM_NAV_LAYOUT = "bottom_navigation_layout"
        private const val KEY_BOTTOM_NAV_ORDER = "bottom_navigation_order"
        val DEFAULT_BOTTOM_NAV_ORDER = listOf("Mensajes", "Videos", "Social", "Perfil")

        fun isReduceMotionEnabled(context: Context): Boolean = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REDUCE_MOTION, false)
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _messageNotificationsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_MESSAGE_NOTIFICATIONS, true)
    )
    val messageNotificationsEnabled: StateFlow<Boolean> = _messageNotificationsEnabled

    private val _callNotificationsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_CALL_NOTIFICATIONS, true)
    )
    val callNotificationsEnabled: StateFlow<Boolean> = _callNotificationsEnabled

    private val _socialNotificationsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SOCIAL_NOTIFICATIONS, true)
    )
    val socialNotificationsEnabled: StateFlow<Boolean> = _socialNotificationsEnabled

    private val _liveNotificationsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_LIVE_NOTIFICATIONS, true)
    )
    val liveNotificationsEnabled: StateFlow<Boolean> = _liveNotificationsEnabled

    private val _livesInVideoFeedEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_LIVES_IN_VIDEO_FEED, true)
    )
    val livesInVideoFeedEnabled: StateFlow<Boolean> = _livesInVideoFeedEnabled

    private val _livesInSocialEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_LIVES_IN_SOCIAL, true)
    )
    val livesInSocialEnabled: StateFlow<Boolean> = _livesInSocialEnabled

    private val _dataSaverEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_DATA_SAVER, false)
    )
    val dataSaverEnabled: StateFlow<Boolean> = _dataSaverEnabled

    private val _videoAutoplayOnMobile = MutableStateFlow(
        prefs.getBoolean(KEY_VIDEO_AUTOPLAY_MOBILE, true)
    )
    val videoAutoplayOnMobile: StateFlow<Boolean> = _videoAutoplayOnMobile

    private val _autoDownloadOnWifi = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_DOWNLOAD_WIFI, true)
    )
    val autoDownloadOnWifi: StateFlow<Boolean> = _autoDownloadOnWifi

    private val _autoDownloadOnMobile = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_DOWNLOAD_MOBILE, false)
    )
    val autoDownloadOnMobile: StateFlow<Boolean> = _autoDownloadOnMobile

    private val _language = MutableStateFlow(
        prefs.getString(KEY_LANGUAGE, "system") ?: "system"
    )
    val language: StateFlow<String> = _language

    private val _reduceMotionEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_REDUCE_MOTION, false)
    )
    val reduceMotionEnabled: StateFlow<Boolean> = _reduceMotionEnabled

    private val _bottomNavigationLayout = MutableStateFlow(
        prefs.getInt(KEY_BOTTOM_NAV_LAYOUT, 0)
    )
    val bottomNavigationLayout: StateFlow<Int> = _bottomNavigationLayout

    private val _bottomNavigationOrder = MutableStateFlow(loadBottomNavigationOrder())
    val bottomNavigationOrder: StateFlow<List<String>> = _bottomNavigationOrder

    fun setMessageNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MESSAGE_NOTIFICATIONS, enabled).apply()
        _messageNotificationsEnabled.value = enabled
    }

    fun setCallNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALL_NOTIFICATIONS, enabled).apply()
        _callNotificationsEnabled.value = enabled
    }

    fun setSocialNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOCIAL_NOTIFICATIONS, enabled).apply()
        _socialNotificationsEnabled.value = enabled
    }

    fun setLiveNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_NOTIFICATIONS, enabled).apply()
        _liveNotificationsEnabled.value = enabled
    }

    fun setLivesInVideoFeedEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVES_IN_VIDEO_FEED, enabled).apply()
        _livesInVideoFeedEnabled.value = enabled
    }

    fun setLivesInSocialEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVES_IN_SOCIAL, enabled).apply()
        _livesInSocialEnabled.value = enabled
    }

    fun setDataSaverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DATA_SAVER, enabled).apply()
        _dataSaverEnabled.value = enabled
    }

    fun setVideoAutoplayOnMobile(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIDEO_AUTOPLAY_MOBILE, enabled).apply()
        _videoAutoplayOnMobile.value = enabled
    }

    fun setAutoDownloadOnWifi(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_WIFI, enabled).apply()
        _autoDownloadOnWifi.value = enabled
    }

    fun setAutoDownloadOnMobile(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_MOBILE, enabled).apply()
        _autoDownloadOnMobile.value = enabled
    }

    fun setLanguage(language: String) {
        val normalized = language.trim().ifBlank { "system" }
        prefs.edit().putString(KEY_LANGUAGE, normalized).apply()
        _language.value = normalized
    }

    fun setReduceMotionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REDUCE_MOTION, enabled).apply()
        _reduceMotionEnabled.value = enabled
    }

    fun setBottomNavigationLayout(layout: Int) {
        val normalized = layout.coerceIn(0, 3)
        prefs.edit().putInt(KEY_BOTTOM_NAV_LAYOUT, normalized).apply()
        _bottomNavigationLayout.value = normalized
    }

    fun setBottomNavigationOrder(order: List<String>) {
        val normalized = normalizeBottomNavigationOrder(order)
        prefs.edit().putString(KEY_BOTTOM_NAV_ORDER, normalized.joinToString("|")).apply()
        _bottomNavigationOrder.value = normalized
    }

    fun setBottomNavigation(layout: Int, order: List<String>) {
        val normalizedLayout = layout.coerceIn(0, 3)
        val normalizedOrder = normalizeBottomNavigationOrder(order)
        prefs.edit()
            .putInt(KEY_BOTTOM_NAV_LAYOUT, normalizedLayout)
            .putString(KEY_BOTTOM_NAV_ORDER, normalizedOrder.joinToString("|"))
            .apply()
        _bottomNavigationLayout.value = normalizedLayout
        _bottomNavigationOrder.value = normalizedOrder
    }

    private fun loadBottomNavigationOrder(): List<String> {
        val raw = prefs.getString(KEY_BOTTOM_NAV_ORDER, null)
        val parsed = raw
            ?.split("|")
            ?.map { it.trim() }
            .orEmpty()
        return normalizeBottomNavigationOrder(parsed)
    }

    private fun normalizeBottomNavigationOrder(order: List<String>): List<String> {
        val allowed = DEFAULT_BOTTOM_NAV_ORDER
        val selected = order
            .mapNotNull { candidate ->
                allowed.firstOrNull { it.equals(candidate.trim(), ignoreCase = true) }
            }
            .distinct()
        return (selected + allowed.filterNot { it in selected }).take(allowed.size)
    }
}
