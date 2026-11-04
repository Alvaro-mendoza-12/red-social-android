package conectate.com.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.data.localfallback.AccountDeletedException
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Storage contract shared by the feature and account deletion, without depending on its UI. */
internal object AgreementRecoveryPreferences {
    const val NAME = "chat_agreements_private_v1"
    val lock = Any()
    fun open(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, NAME, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    fun accountPrefix(accountId: String): String {
        val owner = accountId.trim().lowercase(Locale.ROOT)
        require(owner.isNotEmpty() && owner.length <= 128)
        return digest(owner) + ":"
    }
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun requireAvailable(preferences: SharedPreferences, accountId: String) {
        if (preferences.getBoolean("deleted:" + accountPrefix(accountId), false)) throw AccountDeletedException()
    }
    fun purgeDeletedAccount(preferences: SharedPreferences, accountId: String) = synchronized(lock) {
        val prefix = accountPrefix(accountId)
        val keys = preferences.all.keys.filter { it.startsWith(prefix) }
        // The tombstone blocks delayed or recreated screens from restoring deleted material.
        if (!preferences.edit().apply {
                keys.forEach { remove(it) }
                putBoolean("deleted:$prefix", true)
            }.commit()) throw IOException("No se pudo completar la limpieza de esta cuenta.")
    }
}
