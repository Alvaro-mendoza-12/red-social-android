package conectate.com.data.groupvoice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Persist before HTTP; a cancelled dialog or lost ACK must retain the same invitation IDs. */
@Singleton
class GroupVoiceIntentStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("group_voice_intents", Context.MODE_PRIVATE)
    private fun key(account: String, group: String) = "$account/$group"
    suspend fun load(account: String, group: String): GroupVoiceIntent? = withContext(Dispatchers.IO) {
        preferences.getString(key(account, group), null)?.let { Json.decodeFromString(GroupVoiceIntent.serializer(), it) }
    }
    suspend fun save(account: String, group: String, intent: GroupVoiceIntent) = withContext(Dispatchers.IO) {
        if (!preferences.edit().putString(key(account, group), Json.encodeToString(GroupVoiceIntent.serializer(), intent)).commit()) {
            throw IOException("No se pudo guardar la invitación. Reintenta antes de enviarla.")
        }
    }
    suspend fun clear(account: String, group: String, expected: String) = withContext(Dispatchers.IO) {
        val k = key(account, group)
        val previous = preferences.getString(k, null)
        if (previous != null && Json.decodeFromString(GroupVoiceIntent.serializer(), previous).requestId == expected &&
            !preferences.edit().remove(k).commit()) {
            preferences.edit().putString(k, previous).commit()
            throw IOException("La sala se creó. Conservamos su confirmación para evitar invitaciones repetidas.")
        }
    }
}
