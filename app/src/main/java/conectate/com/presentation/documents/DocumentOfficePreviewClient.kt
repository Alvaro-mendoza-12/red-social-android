package conectate.com.presentation.documents

import android.content.Context
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.supabase.AuthService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DocumentPreviewEntryPoint { fun authService(): AuthService }

internal data class OfficePdfPreview(val file: File, val pages: Int)

/** The original is never changed; only our authenticated server receives its temporary copy. */
internal object DocumentOfficePreviewClient {
    private val client = OkHttpClient.Builder().dns(PublicBackendDns)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun convert(context: Context, file: File, name: String, tokenOverride: String? = null): Response {
        val auth = EntryPointAccessors.fromApplication(context.applicationContext, DocumentPreviewEntryPoint::class.java).authService()
        val owner = auth.getCurrentUserId()
        val token = tokenOverride ?: auth.getAccessTokenAwait()
        if (token.isNullOrBlank()) throw IOException("Inicia sesión para abrir el diseño del documento. Puedes leer el contenido sin esta vista.")
        val base = RuntimeBackendEndpoint.baseUrlFlow.value.trimEnd('/')
        require(base.startsWith("https://")) { "La vista de Office requiere una conexión segura con Novvin." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", safeDocumentName(name), file.asRequestBody("application/octet-stream".toMediaType())).build()
        val response = client.newCall(Request.Builder().url("$base/v1/documents/preview")
            .header("Authorization", "Bearer $token").post(body).build()).awaitDocumentResponse()
        if (tokenOverride == null && owner != auth.getCurrentUserId()) {
            response.close(); throw CancellationException("The document account changed")
        }
        if (!response.isSuccessful) {
            val status = response.code; response.close()
            throw IOException(when (status) {
                401, 403 -> "Renueva tu sesión para abrir el diseño del documento."
                413 -> "El documento es demasiado grande para preparar su vista."
                415, 422 -> "No se pudo interpretar este documento. Comprueba que no esté protegido o dañado."
                429 -> "La vista de documentos está ocupada. Reintenta en unos segundos."
                else -> "No se pudo preparar el diseño. Puedes reintentar o leer la vista compatible."
            })
        }
        return response
    }
}
