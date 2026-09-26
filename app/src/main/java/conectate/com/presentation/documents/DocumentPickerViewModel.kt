package conectate.com.presentation.documents

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.documents.DocumentCatalog
import conectate.com.data.documents.DocumentCatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DocumentPickerState(
    val catalog: DocumentCatalog = DocumentCatalog(),
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DocumentPickerViewModel @Inject constructor(private val repository: DocumentCatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow(DocumentPickerState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L

    fun refresh() = load()
    fun addFolder(uri: Uri) = load { repository.grantTree(uri) }
    fun addDocument(uri: Uri) = load { repository.addDocument(uri) }
    fun removeFolder(uri: Uri) = load { repository.forgetTree(uri) }

    private fun load(beforeScan: suspend () -> Unit = {}) {
        val request = ++generation
        loadJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                beforeScan()
                val catalog = repository.scan()
                if (request == generation) _state.value = DocumentPickerState(catalog)
            } catch (_: TimeoutCancellationException) {
                if (request == generation) _state.value = _state.value.copy(loading = false,
                    error = "La carpeta tardó demasiado en responder. Reintenta o agrega una carpeta más pequeña.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == generation) _state.value = _state.value.copy(loading = false, error = when (error) {
                    is IllegalArgumentException -> error.message
                    is SecurityException -> "No tenemos acceso persistente a ese archivo o carpeta. Elige otro o vuelve a autorizarlo."
                    else -> "No se pudieron leer los documentos. Comprueba el acceso y vuelve a intentar."
                })
            }
        }
    }

    fun stopLoading() {
        generation++
        loadJob?.cancel()
        _state.value = _state.value.copy(loading = false)
    }
}
