package conectate.com.data.repository

import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Preference-only changes must repaint the inbox without another account or network emission. */
internal fun observeInboxPreferenceChanges(preferences: () -> SharedPreferences): Flow<Unit> = callbackFlow {
    // Resolve the lazy store in the repository's collector, not on the composition thread.
    val prefs = preferences()
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    trySend(Unit)
    awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}.conflate()
