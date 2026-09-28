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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun ensureIdentity(): GuestIdentity {
        savedIdentity()?.let { return it }
        val response = config.httpClient.post("${endpoint()}/v1/guest/session")
        if (!response.status.isSuccess()) error("No pudimos iniciar el modo invitado. Inténtalo nuevamente.")
        val identity = json.decodeFromString<GuestIdentity>(response.bodyAsText())
        require(identity.role == "guest" && identity.id.startsWith("guest_"))
        prefs.edit().putString("id", identity.id).putString("username", identity.username)
            .putString("display_name", identity.displayName).putString("token", identity.accessToken)
            .putLong("expires_at", System.currentTimeMillis() + identity.expiresIn * 1000L).apply()
        return identity
    }

    fun clear() { prefs.edit().clear().apply() }

    fun rememberPendingProfile(id: String) {
        if (id.matches(Regex("[0-9a-fA-F-]{36}"))) {
            conversionPrefs.edit().putString("profile_id", id).apply()
        }
    }

    fun clearPendingProfile() { conversionPrefs.edit().remove("profile_id").apply() }

    fun rememberPendingPreview(type: String, id: String = "") {
        previewPrefs.edit().putString("type", type.take(32)).putString("id", id.take(128)).apply()
    }

    fun consumePendingPreview(): GuestPendingPreview? {
        val type = previewPrefs.getString("type", null) ?: return null
        val id = previewPrefs.getString("id", "").orEmpty()
        previewPrefs.edit().clear().apply()
        return GuestPendingPreview(type, id)
    }

    fun consumePendingProfile(): String? {
        val id = conversionPrefs.getString("profile_id", null)
        conversionPrefs.edit().remove("profile_id").apply()
        return id
    }

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
        val identity = ensureIdentity()
        val response = config.httpClient.get("${endpoint()}/v1/guest/$path") {
            header("Authorization", "Bearer ${identity.accessToken}")
            if (query != null) parameter("q", query.trim().take(50))
        }
        if (!response.status.isSuccess()) error("No pudimos cargar el contenido público. Inténtalo nuevamente.")
        return parse(response.bodyAsText())
    }
}
