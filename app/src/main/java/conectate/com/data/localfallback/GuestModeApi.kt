package conectate.com.data.localfallback

import android.content.Context
import conectate.com.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
import conectate.com.core.network.runReadWithDeadline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

@Serializable
data class GuestIdentity(
    val role: String = "guest",
    val id: String,
    val username: String,
    val displayName: String,
    val accessToken: String,
    val expiresIn: Long,
)

data class GuestPendingPreview(val type: String, val id: String = "")

@Serializable
data class GuestContent(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String = "Usuario",
    @SerialName("user_profile_image") val userProfileImage: String? = null,
    val title: String = "",
    val description: String = "",
    val content: String = "",
    val caption: String = "",
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("video_url") val videoUrl: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("media_urls") val mediaUrls: List<JsonElement> = emptyList(),
)

@Serializable
data class GuestProfile(
    val id: String,
    @SerialName("display_name") val displayName: String = "Usuario",
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
)

@Serializable
data class GuestComment(
    val id: String,
    @SerialName("user_name") val userName: String = "Usuario",
    val content: String = "",
)

@Serializable private data class GuestContentList(val items: List<GuestContent> = emptyList())
@Serializable private data class GuestContentEnvelope(val content: GuestContent)
@Serializable private data class GuestProfileList(val items: List<GuestProfile> = emptyList())
@Serializable private data class GuestProfileEnvelope(val profile: GuestProfile)
@Serializable private data class GuestCommentList(val items: List<GuestComment> = emptyList())

@Singleton
class GuestModeApi @Inject constructor(
    private val config: SupabaseConfig,
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("guest_session", Context.MODE_PRIVATE)
    private val conversionPrefs = context.getSharedPreferences("guest_conversion", Context.MODE_PRIVATE)
    private val previewPrefs = context.getSharedPreferences("guest_preview", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val identityMutex = Mutex()
    private val sessionStateLock = Any()
    private var sessionRevision = 0L
    private val _pendingPreview = MutableStateFlow(readPendingPreview())
    val pendingPreview = _pendingPreview.asStateFlow()
    private suspend fun endpoint(): String = RuntimeBackendEndpoint.authenticationEndpoint()
        ?: error("El servidor no está disponible. Revisa tu conexión e inténtalo nuevamente.")

    fun hasActiveGuest(): Boolean =
        !prefs.getString("token", null).isNullOrBlank() &&
            prefs.getLong("expires_at", 0L) > System.currentTimeMillis() + 30_000L

    fun savedIdentity(): GuestIdentity? {
        if (!hasActiveGuest()) return null
        return GuestIdentity(
            id = prefs.getString("id", "").orEmpty(),
            username = prefs.getString("username", "").orEmpty(),
            displayName = prefs.getString("display_name", "").orEmpty(),
            accessToken = prefs.getString("token", "").orEmpty(),
            expiresIn = (prefs.getLong("expires_at", 0L) - System.currentTimeMillis()) / 1000L,
        )
    }

    suspend fun ensureIdentity(): GuestIdentity = ensureIdentity(currentSessionRevision())

    private suspend fun ensureIdentity(expectedRevision: Long): GuestIdentity = identityMutex.withLock {
        checkSessionRevision(expectedRevision)
        savedIdentity()?.let { return@withLock it }
        runReadWithDeadline(10_000L) { createIdentity(expectedRevision) }.getOrThrow()
    }

    private suspend fun createIdentity(revision: Long): GuestIdentity {
        val response = config.httpClient.post("${endpoint()}/v1/guest/session")
        checkResponse(response.status.value)
        val identity = json.decodeFromString<GuestIdentity>(response.bodyAsText())
        require(identity.role == "guest" && identity.id.startsWith("guest_"))
        coroutineContext.ensureActive()
        persistIdentity(identity, revision)
        return identity
    }

    internal fun currentSessionRevision(): Long = synchronized(sessionStateLock) { sessionRevision }

    private fun checkSessionRevision(expectedRevision: Long) {
        if (currentSessionRevision() != expectedRevision) {
            throw CancellationException("La visita de invitado ya terminó.")
        }
    }

    internal fun persistIdentity(identity: GuestIdentity, expectedRevision: Long) {
        synchronized(sessionStateLock) {
            if (sessionRevision != expectedRevision) {
                throw CancellationException("La sesión de invitado cambió mientras se preparaba la visita.")
            }
            prefs.edit().putString("id", identity.id).putString("username", identity.username)
                .putString("display_name", identity.displayName).putString("token", identity.accessToken)
                .putLong("expires_at", System.currentTimeMillis() + identity.expiresIn * 1000L).apply()
        }
    }

    fun clear() {
        synchronized(sessionStateLock) {
            sessionRevision += 1
            prefs.edit().clear().apply()
        }
    }

    fun rememberPendingProfile(id: String) {
        val validId = validProfileId(id)
        conversionPrefs.edit().apply {
            if (validId == null) remove("profile_id") else putString("profile_id", validId)
        }.apply()
    }

    fun clearPendingProfile() { conversionPrefs.edit().remove("profile_id").apply() }

    fun rememberPendingPreview(type: String, id: String = "") {
        synchronized(sessionStateLock) {
            val preview = GuestPendingPreview(type.take(32), id.take(128))
            previewPrefs.edit().putString("type", preview.type).putString("id", preview.id).apply()
            _pendingPreview.value = preview
        }
    }

    private fun readPendingPreview(): GuestPendingPreview? {
        val type = previewPrefs.getString("type", null) ?: return null
        val id = previewPrefs.getString("id", "").orEmpty()
        return GuestPendingPreview(type, id)
    }

    fun consumePendingPreview(): GuestPendingPreview? = synchronized(sessionStateLock) {
        val preview = _pendingPreview.value
        previewPrefs.edit().clear().apply()
        _pendingPreview.value = null
        preview
    }

    fun consumePendingProfile(): String? {
        val id = conversionPrefs.getString("profile_id", null)
        conversionPrefs.edit().remove("profile_id").apply()
        return id?.let(::validProfileId)
    }

    private fun validProfileId(id: String): String? = runCatching {
        UUID.fromString(id).toString().takeIf { it.equals(id, ignoreCase = true) }
    }.getOrNull()

    suspend fun videos(): List<GuestContent> = read("videos") {
        json.decodeFromString<GuestContentList>(it).items
    }

    suspend fun posts(): List<GuestContent> = read("posts") {
        json.decodeFromString<GuestContentList>(it).items
    }

    suspend fun moments(): List<GuestContent> = read("moments") {
        json.decodeFromString<GuestContentList>(it).items
    }

    suspend fun content(kind: String, id: String): GuestContent {
        require(kind == "videos" || kind == "posts")
        return read("$kind/${id.filter { it.isLetterOrDigit() || it == '-' }}") {
            json.decodeFromString<GuestContentEnvelope>(it).content
        }
    }

    suspend fun searchProfiles(query: String): List<GuestProfile> = read("profiles/search", query) {
        json.decodeFromString<GuestProfileList>(it).items
    }

    suspend fun profile(id: String): GuestProfile = read("profiles/${id.filter { it.isLetterOrDigit() || it == '-' }}") {
        json.decodeFromString<GuestProfileEnvelope>(it).profile
    }

    suspend fun comments(kind: String, id: String): List<GuestComment> {
        require(kind == "videos" || kind == "posts")
        return read("$kind/${id.filter { it.isLetterOrDigit() || it == '-' }}/comments") {
            json.decodeFromString<GuestCommentList>(it).items
        }
    }

    private suspend fun <T> read(path: String, query: String? = null, parse: (String) -> T): T {
        val revision = currentSessionRevision()
        return runReadWithDeadline(12_000L) {
            val base = endpoint()
            var identity = ensureIdentity(revision)
            var response = config.httpClient.get("$base/v1/guest/$path") {
                header("Authorization", "Bearer ${identity.accessToken}")
                if (query != null) parameter("q", query.trim().take(50))
            }
            checkSessionRevision(revision)
            if (response.status.value == 401) {
                identityMutex.withLock {
                    // Another parallel request may already have renewed the session.
                    synchronized(sessionStateLock) {
                        checkSessionRevision(revision)
                        if (savedIdentity()?.accessToken == identity.accessToken) prefs.edit().clear().apply()
                    }
                }
                identity = ensureIdentity(revision)
                response = config.httpClient.get("$base/v1/guest/$path") {
                    header("Authorization", "Bearer ${identity.accessToken}")
                    if (query != null) parameter("q", query.trim().take(50))
                }
            }
            checkSessionRevision(revision)
            checkResponse(response.status.value)
            parse(response.bodyAsText())
        }.getOrThrow()
    }

    private fun checkResponse(status: Int) {
        if (status in 200..299) return
        error(when (status) {
            404 -> "Este contenido ya no está disponible o es privado."
            429 -> "Has realizado varias consultas seguidas. Espera un momento y vuelve a intentarlo."
            401 -> "No pudimos renovar tu visita. Vuelve a entrar como invitado."
            else -> "El servidor no pudo cargar el contenido. Inténtalo nuevamente."
        })
    }
}
