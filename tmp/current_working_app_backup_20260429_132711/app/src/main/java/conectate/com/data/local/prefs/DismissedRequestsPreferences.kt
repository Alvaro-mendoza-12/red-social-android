package conectate.com.data.local.prefs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DismissedRequestsPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("friend_requests_prefs", Context.MODE_PRIVATE)
    
    fun getDismissedRequests(): Set<String> {
        return prefs.getStringSet("dismissed_ids", emptySet()) ?: emptySet()
    }
    
    fun dismissRequest(userId: String) {
        val current = getDismissedRequests().toMutableSet()
        current.add(userId)
        prefs.edit().putStringSet("dismissed_ids", current).apply()
    }
}


