package conectate.com.presentation.posts

import conectate.com.data.model.PostReactionUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PostReactionUsersState(val postId: String? = null,
    val users: List<PostReactionUser> = emptyList(), val loading: Boolean = false, val error: String? = null)

internal class PostReactionUsersLoader(private val scope: CoroutineScope,
    private val currentAccount: () -> String?,
    private val fetch: suspend (String, String?) -> Result<List<PostReactionUser>>) {
    private val mutable = MutableStateFlow(PostReactionUsersState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var revision = 0L
    fun clear() { revision++; request?.cancel(); mutable.value = PostReactionUsersState() }
    fun load(postId: String, type: String? = null) {
        request?.cancel(); val version = ++revision; val owner = currentAccount()
        mutable.value = PostReactionUsersState(postId, loading = true)
        request = scope.launch {
            try {
                val result = fetch(postId, type)
                if (version != revision) return@launch
                if (owner != currentAccount()) { mutable.value = PostReactionUsersState(); return@launch }
                mutable.value = result.fold(
                    { PostReactionUsersState(postId, it.distinctBy { user -> user.userId.lowercase() }) },
                    { PostReactionUsersState(postId, error = "No se pudieron cargar las reacciones. Reintenta.") })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (version == revision && owner == currentAccount()) mutable.value =
                    PostReactionUsersState(postId, error = "No se pudieron cargar las reacciones. Reintenta.")
            }
        }
    }
}
