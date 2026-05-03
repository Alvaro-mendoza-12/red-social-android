package conectate.com.data.supabase

import io.github.jan.supabase.gotrue.SessionManager
import io.github.jan.supabase.gotrue.user.UserSession

class NoOpSessionManager : SessionManager {
    override suspend fun saveSession(session: UserSession) {
        // No-op: Do not save anything
    }

    override suspend fun loadSession(): UserSession? {
        // No-op: Always return null (no session found)
        return null
    }

    override suspend fun deleteSession() {
        // No-op
    }
}
