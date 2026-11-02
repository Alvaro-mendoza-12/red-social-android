package conectate.com.presentation.messages

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ShareRecoveryState(
    val requestId: String?, val mode: Mode,
    val plan: List<ShareBatchItem> = emptyList(), val accepted: Int = 0, val message: String? = null,
) {
    enum class Mode { LOADING, EDITABLE, PENDING, CLOSED, FAILED, OTHER_ACCOUNT }
    val blocked: Boolean get() = mode !in setOf(Mode.EDITABLE, Mode.PENDING)
}

/** Recovery has an identity of its own: an old disk read cannot unlock a replacement share. */
internal class ShareBatchRecovery {
    private val _state = MutableStateFlow(ShareRecoveryState(null, ShareRecoveryState.Mode.LOADING))
    val state = _state.asStateFlow()
    private var revision = 0L

    fun reset(id: String?) { revision++; _state.value = ShareRecoveryState(id, ShareRecoveryState.Mode.LOADING) }

    fun frozen(id: String, saved: SavedShareBatch) {
        if (_state.value.requestId == id) _state.value = ShareRecoveryState(id, ShareRecoveryState.Mode.PENDING, saved.plan, saved.receipts.size)
    }

    suspend fun load(id: String, requireCurrent: () -> Unit, read: suspend () -> SavedShareBatch?,
        restore: (SavedShareBatch?) -> Unit) {
        reset(id)
        val expected = revision
        fun guard() {
            requireCurrent()
            if (revision != expected) throw CancellationException("La solicitud de compartir cambió.")
        }
        try {
            val saved = read()
            guard()
            restore(saved)
            _state.value = when {
                saved?.closed == true -> ShareRecoveryState(id, ShareRecoveryState.Mode.CLOSED)
                saved != null -> ShareRecoveryState(id, ShareRecoveryState.Mode.PENDING, saved.plan, saved.receipts.size)
                else -> ShareRecoveryState(id, ShareRecoveryState.Mode.EDITABLE)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            guard()
            _state.value = ShareRecoveryState(id, if (error is ShareBatchOwnerMismatch) ShareRecoveryState.Mode.OTHER_ACCOUNT
                else ShareRecoveryState.Mode.FAILED, message = if (error is ShareBatchOwnerMismatch) error.message
                else "No se pudo recuperar el envío anterior. Compruébalo de nuevo antes de continuar.")
        }
    }
}
