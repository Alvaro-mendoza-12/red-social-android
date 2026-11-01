package conectate.com.data.repository

import conectate.com.data.model.StoredAccountSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serialize credential edits and publish only snapshots confirmed by secure storage. */
internal class StoredAccountsStore(
    initial: List<StoredAccountSession>,
    private val persist: (List<StoredAccountSession>) -> Unit,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()

    fun update(transform: (List<StoredAccountSession>) -> List<StoredAccountSession>): List<StoredAccountSession> =
        synchronized(lock) {
            val next = transform(mutable.value)
            persist(next)
            mutable.value = next
            next
        }

    fun retainRefreshed(
        expected: StoredAccountSession,
        refreshed: StoredAccountSession,
    ): StoredAccountSession {
        var retained: StoredAccountSession? = null
        update { accounts ->
            val current = accounts.firstOrNull { it.id == expected.id }
                ?: throw IllegalStateException("La cuenta guardada se quitó durante la renovación. Vuelve a seleccionarla.")
            if (current.access_token != expected.access_token || current.refresh_token != expected.refresh_token)
                throw IllegalStateException("La sesión guardada se actualizó durante la renovación. Vuelve a seleccionarla.")
            val next = current.copy(
                user_id = refreshed.user_id,
                email = refreshed.email,
                access_token = refreshed.access_token,
                refresh_token = refreshed.refresh_token,
                display_name = refreshed.display_name ?: current.display_name,
                avatar_url = refreshed.avatar_url ?: current.avatar_url,
                last_used_at = refreshed.last_used_at,
            )
            retained = next
            accounts.map { if (it.id == current.id) next else it }.sortedByDescending { it.last_used_at }
        }
        return checkNotNull(retained)
    }
}
