package conectate.com.data.local.prefs

import android.content.Context
import android.app.backup.BackupManager
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable, user-initiated settings backup. It deliberately excludes account
 * sessions, authentication tokens, messages, media and server content.
 */
@Singleton
class SettingsBackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val themePreferences: ThemePreferences,
    private val appSettingsPreferences: AppSettingsPreferences,
    private val messagesPreferences: MessagesPreferences,
    private val contentSafetyPreferences: ContentSafetyPreferences,
) {
    fun exportTo(uri: Uri, accountEmail: String? = null): Result<Unit> = runCatching {
        val payload = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", Instant.now().toString())
            .put("accountEmail", accountEmail?.trim()?.lowercase().orEmpty())
            .put("scope", "preferences_only")
            .put("theme", JSONObject()
                .put("dark", themePreferences.isDarkMode.value)
                .put("appTheme", themePreferences.appTheme.value.storageValue)
                .put("avatarVideosMuted", themePreferences.profileAvatarVideosMuted.value)
                .put("font", themePreferences.fontOption.value.storageValue)
                .put("textScale", themePreferences.textScale.value))
            .put("app", JSONObject()
                .put("messageNotifications", appSettingsPreferences.messageNotificationsEnabled.value)
                .put("callNotifications", appSettingsPreferences.callNotificationsEnabled.value)
                .put("socialNotifications", appSettingsPreferences.socialNotificationsEnabled.value)
                .put("liveNotifications", appSettingsPreferences.liveNotificationsEnabled.value)
                .put("livesInVideoFeed", appSettingsPreferences.livesInVideoFeedEnabled.value)
                .put("livesInSocial", appSettingsPreferences.livesInSocialEnabled.value)
                .put("dataSaver", appSettingsPreferences.dataSaverEnabled.value)
                .put("videoAutoplayMobile", appSettingsPreferences.videoAutoplayOnMobile.value)
                .put("autoDownloadWifi", appSettingsPreferences.autoDownloadOnWifi.value)
                .put("autoDownloadMobile", appSettingsPreferences.autoDownloadOnMobile.value)
                .put("language", appSettingsPreferences.language.value)
                .put("reduceMotion", appSettingsPreferences.reduceMotionEnabled.value)
                .put("bottomNavigationLayout", appSettingsPreferences.bottomNavigationLayout.value)
                .put("bottomNavigationOrder", JSONArray(appSettingsPreferences.bottomNavigationOrder.value)))
            .put("messages", JSONObject()
                .put("compact", messagesPreferences.compactMode.value)
                .put("sounds", messagesPreferences.chatSoundsEnabled.value)
                .put("typing", messagesPreferences.typingIndicatorsEnabled.value)
                .put("crossAccountSuggestions", messagesPreferences.crossAccountSuggestionsEnabled.value)
                .put("connectivityHints", messagesPreferences.connectivityHintsEnabled.value)
                .put("enterToSend", messagesPreferences.enterToSendEnabled.value)
                .put("autoDownload", messagesPreferences.mediaAutoDownloadEnabled.value)
                .put("readReceipts", messagesPreferences.readReceiptsEnabled.value)
                .put("accent", messagesPreferences.chatAccent.value)
                .put("background", messagesPreferences.chatBackground.value))
            .put("safety", JSONObject()
                .put("sensitiveMode", contentSafetyPreferences.sensitiveContentMode.value.storageValue)
                .put("adultConfirmed", contentSafetyPreferences.adultConfirmed.value)
                .put("moderationWarnings", contentSafetyPreferences.moderationWarningsEnabled.value))

        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: error("No se pudo abrir el destino elegido.")
        output.buffered().use { stream ->
            stream.write(MAGIC)
            GZIPOutputStream(stream).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }
        }
    }

    fun importFrom(uri: Uri): Result<Unit> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_BACKUP_BYTES) { "La copia es demasiado grande." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("No se pudo leer la copia seleccionada.")
        val raw = if (bytes.startsWith(MAGIC)) {
            GZIPInputStream(ByteArrayInputStream(bytes, MAGIC.size, bytes.size - MAGIC.size))
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } else {
            // Compatibility with copies created by earlier closed-test builds.
            bytes.toString(Charsets.UTF_8)
        }
        val payload = JSONObject(raw)
        require(payload.optString("format") == FORMAT) { "El archivo no es una copia de Novvin." }
        require(payload.optInt("version") in 1..VERSION) { "La versión de la copia no es compatible." }

        payload.optJSONObject("theme")?.let { value ->
            themePreferences.setDarkMode(value.optBoolean("dark", true))
            themePreferences.setAppTheme(AppThemeOption.fromStorage(value.optString("appTheme")))
            themePreferences.setProfileAvatarVideosMuted(value.optBoolean("avatarVideosMuted", true))
            themePreferences.setFontOption(AppFontOption.fromStorage(value.optString("font")))
            themePreferences.setTextScale(value.optDouble("textScale", 1.0).toFloat())
        }
        payload.optJSONObject("app")?.let { value ->
            appSettingsPreferences.setMessageNotificationsEnabled(value.optBoolean("messageNotifications", true))
            appSettingsPreferences.setCallNotificationsEnabled(value.optBoolean("callNotifications", true))
            appSettingsPreferences.setSocialNotificationsEnabled(value.optBoolean("socialNotifications", true))
            appSettingsPreferences.setLiveNotificationsEnabled(value.optBoolean("liveNotifications", true))
            appSettingsPreferences.setLivesInVideoFeedEnabled(value.optBoolean("livesInVideoFeed", true))
            appSettingsPreferences.setLivesInSocialEnabled(value.optBoolean("livesInSocial", true))
            appSettingsPreferences.setDataSaverEnabled(value.optBoolean("dataSaver", false))
            appSettingsPreferences.setVideoAutoplayOnMobile(value.optBoolean("videoAutoplayMobile", true))
            appSettingsPreferences.setAutoDownloadOnWifi(value.optBoolean("autoDownloadWifi", true))
            appSettingsPreferences.setAutoDownloadOnMobile(value.optBoolean("autoDownloadMobile", false))
            appSettingsPreferences.setLanguage(value.optString("language", "system"))
            appSettingsPreferences.setReduceMotionEnabled(value.optBoolean("reduceMotion", false))
            appSettingsPreferences.setBottomNavigation(
                value.optInt("bottomNavigationLayout", 0),
                value.optJSONArray("bottomNavigationOrder").asStringList(),
            )
        }
        payload.optJSONObject("messages")?.let { value ->
            messagesPreferences.setCompactMode(value.optBoolean("compact", false))
            messagesPreferences.setChatSoundsEnabled(value.optBoolean("sounds", true))
            messagesPreferences.setTypingIndicatorsEnabled(value.optBoolean("typing", true))
            messagesPreferences.setCrossAccountSuggestionsEnabled(value.optBoolean("crossAccountSuggestions", true))
            messagesPreferences.setConnectivityHintsEnabled(value.optBoolean("connectivityHints", true))
            messagesPreferences.setEnterToSendEnabled(value.optBoolean("enterToSend", false))
            messagesPreferences.setMediaAutoDownloadEnabled(value.optBoolean("autoDownload", true))
            messagesPreferences.setReadReceiptsEnabled(value.optBoolean("readReceipts", true))
            messagesPreferences.setChatAccent(value.optString("accent", "conectate_red"))
            messagesPreferences.setChatBackground(value.optString("background", "deep_black"))
        }
        payload.optJSONObject("safety")?.let { value ->
            contentSafetyPreferences.setAdultConfirmed(value.optBoolean("adultConfirmed", false))
            contentSafetyPreferences.setSensitiveContentMode(SensitiveContentMode.fromStorage(value.optString("sensitiveMode")))
            contentSafetyPreferences.setModerationWarningsEnabled(value.optBoolean("moderationWarnings", true))
        }
    }

    fun scheduleAndroidBackup(accountEmail: String? = null): Result<Instant> = runCatching {
        val requestedAt = Instant.now()
        BackupManager(context).dataChanged()
        context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_REQUESTED_AT, requestedAt.toString())
            .putString(KEY_ACCOUNT_EMAIL, accountEmail?.trim()?.lowercase().orEmpty())
            .apply()
        requestedAt
    }

    fun lastAndroidBackupRequest(): Instant? = context
        .getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_LAST_REQUESTED_AT, null)
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun JSONArray?.asStringList(): List<String> = buildList {
        val source = this@asStringList ?: return@buildList
        for (index in 0 until source.length()) source.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private companion object {
        const val FORMAT = "novvin-settings-backup"
        const val VERSION = 1
        const val MAX_BACKUP_BYTES = 256_000
        const val STATUS_PREFS = "backup_status"
        const val KEY_LAST_REQUESTED_AT = "last_requested_at"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        val MAGIC = "NOVVIN_BACKUP_V2\n".toByteArray(Charsets.US_ASCII)
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
