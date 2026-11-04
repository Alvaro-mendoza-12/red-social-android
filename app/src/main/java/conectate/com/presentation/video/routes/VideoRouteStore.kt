package conectate.com.presentation.video.routes

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val VIDEO_ROUTE_PREFERENCES = "video_routes_private_v1"

internal fun encryptedVideoRoutePreferences(context: Context): SharedPreferences {
    val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
        context, VIDEO_ROUTE_PREFERENCES, key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

/** All callers share this mutex and the same account-transition guard as authentication. */
@Singleton
class VideoRouteStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: AuthService,
) {
    private val mutex = Mutex()
    private val journal by lazy { VideoRouteJournal(encryptedVideoRoutePreferences(context)) }

    internal suspend fun read(lease: AuthSessionLease): VideoRoute? = withContext(Dispatchers.IO) {
        mutex.withLock {
            lease.requireCurrent()
            journal.read(checkNotNull(lease.accountId)).also { lease.requireCurrent() }
        }
    }

    internal suspend fun replace(lease: AuthSessionLease, expected: VideoRoute?, next: VideoRoute?) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lease.requireCurrent()
                val accepted = auth.commitForAccountLease(lease) {
                    journal.replace(checkNotNull(lease.accountId), expected, next)
                }
                if (!accepted) throw CancellationException("La sesión cambió. Vuelve a abrir Rutas.")
            }
        }

    internal suspend fun erase(lease: AuthSessionLease) = withContext(Dispatchers.IO) {
        mutex.withLock {
            lease.requireCurrent()
            val accepted = auth.commitForAccountLease(lease) { journal.erase(checkNotNull(lease.accountId)) }
            if (!accepted) throw CancellationException("La sesión cambió. Vuelve a abrir Rutas.")
        }
    }
}

/** Persistence is separate so real preference failures and process recreation can be tested. */
internal class VideoRouteJournal(private val preferences: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(accountId: String): VideoRoute? {
        val raw = preferences.getString(key(accountId), null) ?: return null
        require(raw.length <= 20_000) { "La ruta guardada no se pudo leer." }
        // Preserve an unreadable or newer record until the user explicitly chooses to erase it.
        return json.decodeFromString<VideoRoute>(raw).validate()
    }

    fun replace(accountId: String, expected: VideoRoute?, next: VideoRoute?) {
        val current = read(accountId)
        check(current?.id == expected?.id && current?.revision == expected?.revision) {
            "La ruta cambió. Vuelve a cargarla antes de guardar."
        }
        next?.validate()
        if (current != null && next?.id == current.id) require(next.revision == Math.addExact(current.revision, 1))
        write(key(accountId), next?.let { json.encodeToString(it) })
    }

    fun erase(accountId: String) = write(key(accountId), null)

    private fun write(storageKey: String, value: String?) {
        val previous = preferences.getString(storageKey, null)
        if (!preferences.edit().putString(storageKey, value).commit()) {
            // commit can change the in-memory preferences before reporting a disk failure.
            // Restore that view too, so a failed write cannot become an apparent acknowledgement.
            preferences.edit().putString(storageKey, previous).commit()
            throw IOException("No se pudo guardar la ruta en este dispositivo.")
        }
    }

    private fun key(accountId: String): String {
        val account = routeIdentity(accountId)
        require(account.isNotBlank())
        return MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
