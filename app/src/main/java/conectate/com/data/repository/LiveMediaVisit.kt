package conectate.com.data.repository

import conectate.com.data.model.LiveRole
import conectate.com.data.model.LiveSource
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.webrtc.WebRTCManager
import io.socket.client.Socket
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** One media lifetime, including a private preview promoted to its own LIVE. */
internal class LiveMediaVisit(
    val account: AuthSessionLease,
    initialAccessToken: String,
    private val commitAccount: (AuthSessionLease, () -> Unit) -> Boolean,
    private val readAccessToken: () -> String?,
) {
    val id: String = UUID.randomUUID().toString()
    val userId: String = requireNotNull(account.accountId)
    @Volatile var closing = false
    @Volatile var startupJob: Job? = null
    @Volatile var manager: WebRTCManager? = null
    @Volatile var socket: Socket? = null
    @Volatile var liveId: String? = null
    @Volatile var role: LiveRole? = null
    @Volatile var screenEntry: LiveScreenEntry? = null
    @Volatile var rendererDetachRequired = false
    @Volatile var previewSource: LiveSource? = null
    @Volatile var closeResult: Result<Unit>? = null
    var nativeRelease: CompletableDeferred<Unit>? = null
    val collectors = mutableListOf<Job>()
    @Volatile private var lastOwnedAccessToken = initialAccessToken.trim()

    fun isCurrent(): Boolean = !closing && account.isCurrent()
    fun belongsTo(request: AuthSessionLease): Boolean = account.sameVisit(request) && isCurrent()

    /** Renewal may update this visit, but another account/ABA can never lend it a token. */
    fun accessToken(): String? {
        var captured: String? = null
        commitAccount(account) {
            if (!closing) {
                captured = readAccessToken()?.trim()?.takeIf(String::isNotEmpty)
                captured?.let { lastOwnedAccessToken = it }
            }
        }
        return captured
    }

    /** Best-effort departure always uses its own last credential, even after invalidation. */
    fun outgoingAccessToken(): String = accessToken() ?: lastOwnedAccessToken
}
