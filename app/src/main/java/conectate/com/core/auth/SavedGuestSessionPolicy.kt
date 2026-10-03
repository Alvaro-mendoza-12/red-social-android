package conectate.com.core.auth

import android.content.Context

/** Reads preferences without constructing the guest API or its network dependencies. */
internal fun hasSavedGuestSession(context: Context): Boolean {
    val preferences = context.getSharedPreferences("guest_session", Context.MODE_PRIVATE)
    return isSavedGuestSessionActive(!preferences.getString("token", null).isNullOrBlank(),
        preferences.getLong("expires_at", 0), System.currentTimeMillis())
}

internal fun isSavedGuestSessionActive(hasToken: Boolean, expiresAt: Long, now: Long): Boolean =
    hasToken && expiresAt > now + 30_000L
