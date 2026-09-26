package conectate.com.presentation.social

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import conectate.com.core.media.copyMediaWithLimit
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.Moment
import conectate.com.data.supabase.AuthService
import conectate.com.util.MediaPlaybackDataSources
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MomentDownloadViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: AuthService,
) : ViewModel() {
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var pendingMoment: Moment? = null
    private var pendingAccount: String? = null
    private var download: Job? = null

    fun prepare(moment: Moment): Boolean {
        if (_saving.value || pendingMoment != null || auth.getCurrentUserId().isNullOrBlank() || !moment.isVideoMoment()) return false
        pendingMoment = moment
        pendingAccount = auth.getCurrentUserId()
        return true
    }

    fun saveTo(destination: Uri?) {
        val moment = pendingMoment
        val account = pendingAccount
        pendingMoment = null
        pendingAccount = null
        if (destination == null) return
        if (moment == null || account == null || account != auth.getCurrentUserId()) {
            viewModelScope.launch(Dispatchers.IO) { deletePartial(destination) }
            _message.value = "La sesión cambió. Abre el momento e inténtalo de nuevo."
            return
        }
        _saving.value = true
        download = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val url = UbuntuStorageUrl.rewrite(moment.mediaUrl)
                        ?: error("El video ya no está disponible.")
                    val uri = Uri.parse(url)
                    require(uri.scheme in setOf("https", "http")) { "El video no tiene una dirección válida." }
                    val source = MediaPlaybackDataSources.httpFactory().createDataSource()
                    val coroutine = currentCoroutineContext()
                    DataSourceInputStream(source, DataSpec(uri)).use { input ->
                        input.open()
                        val target = momentExportTarget(moment.id, moment.mediaUrl, moment.mediaType)
                        val expectedBytes = validateMomentExportResponse(target, source.responseHeaders, MAX_EXPORT_BYTES)
                        context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                            val copied = copyMediaWithLimit(input, output, MAX_EXPORT_BYTES) {
                                coroutine.ensureActive()
                                check(auth.getCurrentUserId() == account) { "La sesión cambió durante la descarga." }
                            }
                            validateMomentExportSize(copied, expectedBytes)
                            coroutine.ensureActive()
                            check(auth.getCurrentUserId() == account) { "La sesión cambió durante la descarga." }
                        } ?: error("No se pudo abrir el destino elegido.")
                    }
                }
                check(auth.getCurrentUserId() == account) { "La sesión cambió durante la descarga." }
                _message.value = "Video guardado en la ubicación elegida."
            } catch (cancelled: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { deletePartial(destination) }
                _message.value = "Descarga cancelada."
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { deletePartial(destination) }
                _message.value = "No se pudo guardar el video. Comprueba la conexión y el espacio disponible."
            } finally {
                _saving.value = false
            }
        }
    }

    fun cancel() { download?.cancel() }
    fun consumeMessage() { _message.value = null }
    private fun deletePartial(uri: Uri) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }

    private companion object { const val MAX_EXPORT_BYTES = 80L * 1024 * 1024 }
}
