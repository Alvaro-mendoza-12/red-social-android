package conectate.com.data.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Shared by realtime, FCM and the cold-start ringing service. */
class CallCancellationGuard(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("call_notification_guard", Context.MODE_PRIVATE)

    fun mark(callId: String) {
        val id = callId.trim().lowercase()
        if (id.isBlank()) return
        val now = System.currentTimeMillis()
        val editor = preferences.edit()
        preferences.all.forEach { (key, value) ->
            if (key.startsWith(PREFIX) && !isRecentCallCancellation(value as? Long ?: 0, now)) editor.remove(key)
        }
        editor.putLong(PREFIX + id, now).apply()
    }

    fun contains(callId: String): Boolean {
        val id = callId.trim().lowercase()
        return id.isNotBlank() && isRecentCallCancellation(preferences.getLong(PREFIX + id, 0), System.currentTimeMillis())
    }

    /** Closes a cold-start answer screen without constructing CallRepository/WebRTC. */
    fun observe(callId: String): Flow<Boolean> = callbackFlow {
        val id = callId.trim().lowercase()
        val key = PREFIX + id
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed == key) trySend(contains(id))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        // Register first so cancellation cannot fall between the initial read and listening.
        trySend(contains(id))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private companion object { const val PREFIX = "cancelled_call_" }
}

internal fun isRecentCallCancellation(timestamp: Long, now: Long): Boolean =
    timestamp > 0 && now - timestamp in -120_000L..120_000L
