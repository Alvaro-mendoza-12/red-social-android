package conectate.com.presentation.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

data class EmailRecoveryDraft(
    val email: String,
    val code: String = "",
    val resetToken: String = "",
    val expiresAt: Long,
    val resendAt: Long = 0,
) {
    val verified: Boolean get() = resetToken.isNotBlank()
}

/** Recovery progress survives process recreation; passwords are never stored here. */
class EmailRecoveryDraftStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences by lazy {
        val key = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(appContext, "novvin_email_recovery", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    fun read(): EmailRecoveryDraft? = runCatching {
        val raw = preferences.getString("draft", null) ?: return@runCatching null
        val data = JSONObject(raw)
        val draft = EmailRecoveryDraft(data.getString("email"), data.optString("code"),
            data.optString("resetToken"), data.getLong("expiresAt"), data.optLong("resendAt"))
        if (draft.expiresAt <= System.currentTimeMillis()) { clear(); null } else draft
    }.getOrNull()
    fun save(draft: EmailRecoveryDraft) {
        val data = JSONObject().put("email", draft.email).put("code", draft.code)
            .put("resetToken", draft.resetToken).put("expiresAt", draft.expiresAt).put("resendAt", draft.resendAt)
        check(preferences.edit().putString("draft", data.toString()).commit()) { "No se pudo guardar el avance de recuperación." }
    }
    fun clear() { check(preferences.edit().remove("draft").commit()) }
}
