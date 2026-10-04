package conectate.com.data.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

/** A Commons file whose reuse terms were verified from the API response. */
@Serializable
data class ApprovedMomentMusic(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val audioUrl: String,
    val sourcePageUrl: String,
    val source: String = SOURCE_WIKIMEDIA_COMMONS,
    val license: String,
    val licenseUrl: String? = null,
    val artworkUrl: String? = null,
    val categories: List<String> = emptyList(),
    val album: String? = null,
    val previewUrl: String? = null,
    val provider: String = source,
    val attribution: String? = null,
    val waveformUrl: String? = null,
    val allowUseInMoments: Boolean = true,
    val allowRemix: Boolean = true,
    val isFavorite: Boolean = false,
    val ownerId: String? = null,
    val lyrics: String? = null,
) {
    companion object {
        const val SOURCE_WIKIMEDIA_COMMONS = "wikimedia_commons"
    }
}

data class MomentMusicCatalogPage(
    val items: List<ApprovedMomentMusic>,
    val nextCursor: String?,
)

/**
 * Public, keyless catalog for Momentos. Every result is rejected unless the
 * license in Commons extmetadata is explicitly CC0 or Public Domain.
 */
@Singleton
class MomentMusicCatalogRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val communityApi: CommunityMusicApiClient,
    private val authService: AuthService,
    private val storageService: StorageService,
    @param:ApplicationContext private val context: Context,
) {
    suspend fun search(
        query: String,
        category: String? = null,
        cursor: String? = null,
        section: String = "search",
    ): Result<MomentMusicCatalogPage> = coroutineScope {
        val token = authService.getAccessTokenAwait().orEmpty()
        if (token.isNotBlank()) {
            val authoritative = communityApi.catalogSearch(
                token = token,
                query = query,
                category = category,
                section = section,
                cursor = cursor,
            )
            authoritative.getOrNull()?.let { page ->
                return@coroutineScope Result.success(
                    MomentMusicCatalogPage(
                        items = page.items.filter(::isApproved),
                        nextCursor = page.nextCursor,
                    ),
                )
            }
        }
        // The Moment picker deliberately exposes only the local-device library
        // and Novvin Community. Never replace a failed community request with
        // a third-party provider, because that would silently change sources.
        if (section in setOf("community", "mine")) {
            val fallbackToken = authService.getAccessTokenAwait().orEmpty()
            if (fallbackToken.isBlank()) {
                return@coroutineScope Result.failure(IllegalStateException("AUTH_REQUIRED"))
            }
            return@coroutineScope communityApi.search(fallbackToken, query, category, cursor).map { page ->
                MomentMusicCatalogPage(
                    items = page.items.mapNotNull(CommunityMusicTrack::toApprovedMomentMusic),
                    nextCursor = page.nextCursor,
                )
            }
        }
        // Resilient fallback for closed tests. Publishing remains server
        // authoritative and repeats the license/URL verification.
        val cursors = parseCombinedCursor(cursor)
        val community = async {
            val fallbackToken = authService.getAccessTokenAwait().orEmpty()
            if (fallbackToken.isBlank()) Result.failure(IllegalStateException("AUTH_REQUIRED"))
            else communityApi.search(fallbackToken, query, category, cursors.second)
        }
        val commons = async { searchCommons(query, category, cursors.first) }
        val communityResult = community.await()
        val commonsResult = commons.await()
        val communityPage = communityResult.getOrNull()
        val commonsPage = commonsResult.getOrNull()
        if (communityPage == null && commonsPage == null) {
            return@coroutineScope Result.failure(
                communityResult.exceptionOrNull() ?: commonsResult.exceptionOrNull()
                ?: IllegalStateException("No se pudo cargar el catálogo musical."),
            )
        }
        val communityTracks = communityPage?.items.orEmpty().mapNotNull(CommunityMusicTrack::toApprovedMomentMusic)
        Result.success(
            MomentMusicCatalogPage(
                items = (communityTracks + commonsPage?.items.orEmpty()).distinctBy { it.id },
                nextCursor = combinedCursor(commonsPage?.nextCursor, communityPage?.nextCursor),
            ),
        )
    }

    private suspend fun searchCommons(
        query: String,
        category: String? = null,
        cursor: String? = null,
    ): Result<MomentMusicCatalogPage> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQuery = query.trim().take(80)
            val cleanCategory = category?.trim()?.take(40).orEmpty()
            val terms = buildList {
                if (cleanQuery.isNotBlank()) add(cleanQuery)
                if (cleanCategory.isNotBlank() && !cleanCategory.equals("Todo", true)) add(cleanCategory)
                if (isEmpty()) add("music")
                // Commons search is deliberately narrowed before parsing. A broad
                // `music` query is mostly CC-BY/CC-BY-SA and left the first page
                // apparently empty after our strict PD/CC0 policy was applied.
                // Public-domain dedications (including CC0) index these terms in
                // UsageTerms/LicenseShortName, while the API metadata remains the
                // authoritative check below.
                add("\"public domain\"")
                add("filetype:audio")
                add("-filemime:audio/midi")
            }.joinToString(" ")

            val response = supabaseConfig.httpClient.get(COMMONS_API) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("action", "query")
                parameter("generator", "search")
                parameter("gsrsearch", terms)
                parameter("gsrnamespace", "6")
                parameter("gsrlimit", "30")
                cursor?.toIntOrNull()?.takeIf { it >= 0 }?.let { parameter("gsroffset", it) }
                parameter("prop", "info|imageinfo")
                parameter("inprop", "url")
                parameter("iiprop", "url|mime|size|extmetadata")
                parameter("iiurlwidth", "240")
                parameter(
                    "iiextmetadatafilter",
                    "Artist|Credit|LicenseShortName|LicenseUrl|UsageTerms|AttributionRequired|Categories|ImageDescription",
                )
                parameter("iiextmetadatalanguage", "es")
                parameter("format", "json")
                parameter("formatversion", "2")
                parameter("origin", "*")
            }
            if (!response.status.isSuccess()) {
                error("Wikimedia Commons respondió ${response.status.value}.")
            }
            val root = response.body<JsonObject>()
            val pages = root["query"]?.jsonObject?.get("pages")?.jsonArray.orEmpty()
            val items = pages.mapNotNull { element -> parseApprovedTrack(element.jsonObject) }
                .distinctBy { it.id }
            val next = root["continue"]?.jsonObject
                ?.get("gsroffset")?.jsonPrimitive?.intOrNull?.toString()
            MomentMusicCatalogPage(items = items, nextCursor = next)
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }
    }

    /** Used by the client before publishing; the server repeats the same checks. */
    fun isApproved(track: ApprovedMomentMusic): Boolean = isApprovedMomentMusicTrack(track)

    suspend fun myCommunityUploads(cursor: String?): Result<CommunityMusicPage> {
        val token = authService.getAccessTokenAwait().orEmpty()
        if (token.isBlank()) return Result.failure(IllegalStateException("AUTH_REQUIRED"))
        // Own history includes private/pending/rejected uploads; never fall back to public search.
        return communityApi.myUploads(token, cursor)
    }

    suspend fun deleteOwnCommunityUpload(trackId: String): Result<Unit> {
        val token = authService.getAccessTokenAwait().orEmpty()
        if (token.isBlank()) return Result.failure(IllegalStateException("AUTH_REQUIRED"))
        return communityApi.deleteOwnUpload(token, trackId).mapCatching { response ->
            check(response.deleted && response.id == trackId) { "MUSIC_DELETE_NOT_CONFIRMED" }
        }
    }

    suspend fun setFavorite(track: ApprovedMomentMusic, favorite: Boolean): Result<Boolean> {
        val token = authService.getAccessTokenAwait().orEmpty()
        if (token.isBlank()) return Result.failure(IllegalStateException("AUTH_REQUIRED"))
        return communityApi.setCatalogFavorite(token, track.id, favorite)
    }

    suspend fun report(track: ApprovedMomentMusic, reason: String, details: String? = null): Result<Unit> {
        val token = authService.getAccessTokenAwait().orEmpty()
        if (token.isBlank()) return Result.failure(IllegalStateException("AUTH_REQUIRED"))
        return communityApi.reportCatalogTrack(token, track.id, reason, details)
    }

    suspend fun notInterested(track: ApprovedMomentMusic): Result<Unit> {
        val token = authService.getAccessTokenAwait().orEmpty()
        if (token.isBlank()) return Result.failure(IllegalStateException("AUTH_REQUIRED"))
        return communityApi.markCatalogNotInterested(token, track.id)
    }

    suspend fun recordPreviewStarted(track: ApprovedMomentMusic): Result<Unit> {
        val token = authService.getAccessTokenAwait().orEmpty()
        if (token.isBlank()) return Result.failure(IllegalStateException("AUTH_REQUIRED"))
        return communityApi.recordCatalogPlay(token, track.id)
    }

    suspend fun uploadCommunityTrack(
        audioUri: Uri,
        coverUri: Uri? = null,
        draft: CommunityMusicUploadDraft,
        onProgress: (Float) -> Unit = {},
    ): Result<CommunityMusicTrack> = withContext(Dispatchers.IO) {
        val uploadedObjects = mutableListOf<Pair<String, String>>()
        var serverTrackCreated = false
        val result = runCatching {
            require(draft.acceptedRights) { "Debes confirmar que controlas los derechos y aceptar las condiciones." }
            require(draft.title.isNotBlank() && draft.artist.isNotBlank()) { "El título y el artista son obligatorios." }
            val resolver = context.contentResolver
            val displayName = resolveCommunityDisplayName(context, audioUri)
            val contentType = canonicalCommunityMusicMime(
                runCatching { resolver.getType(audioUri) }.getOrNull(),
                displayName,
            )
                ?: error("Este formato de audio no es compatible.")
            val sizeBytes = resolveCommunityMusicSize(context, audioUri, COMMUNITY_MUSIC_MAX_BYTES)
            require(sizeBytes in 1..COMMUNITY_MUSIC_MAX_BYTES) { "La pista debe pesar menos de 80 MB." }
            val durationMs = resolveCommunityMusicDuration(context, audioUri)
            require(CommunityMusicPolicy.acceptsDuration(durationMs)) { CommunityMusicPolicy.DURATION_HELP }
            val token = authService.getAccessTokenAwait()?.takeIf(String::isNotBlank)
                ?: error("AUTH_REQUIRED")
            val userId = authService.getCurrentUserId()?.trim()?.takeIf(String::isNotBlank)
                ?: error("AUTH_REQUIRED")
            // Validate and copy the artwork before uploading the audio. Its
            // content provider may omit MIME/name, and the UI owns its cache
            // file: this upload keeps a separate, verified JPEG until finished.
            withPreparedCommunityCoverUpload(context, coverUri) { preparedCover ->
                val extension = communityMusicExtension(contentType)
                val objectName = "$userId/${UUID.randomUUID()}.$extension"
                val uploaded = storageService.uploadFileFromUri(
                    bucketName = "community-music",
                    fileName = objectName,
                    sourceUri = audioUri,
                    sizeBytes = sizeBytes,
                    contentType = contentType,
                    allowOverwrite = false,
                    onProgress = { uploadedFraction ->
                        // El archivo es la fase más pesada, pero no es todo el
                        // proceso: reservamos el último 25 % para validación,
                        // creación del catálogo y publicación autoritativa.
                        onProgress(uploadedFraction.coerceIn(0f, 1f) * 0.75f)
                    },
                ).getOrThrow()
                onProgress(0.76f)
                val remoteKey = uploaded.Key ?: objectName
                uploadedObjects += "community-music" to remoteKey
                val audioUrl = storageService.getPublicUrl("community-music", remoteKey)
                    .takeIf(String::isNotBlank)
                    ?: error("COMMUNITY_MUSIC_PUBLIC_URL_REQUIRED")
                val coverUrl = preparedCover?.let { selectedCover ->
                    val coverName = "$userId/music-covers/${UUID.randomUUID()}.jpg"
                    val coverUpload = storageService.uploadFileFromUri(
                        bucketName = "thumbnails",
                        fileName = coverName,
                        sourceUri = Uri.fromFile(selectedCover),
                        sizeBytes = selectedCover.length(),
                        contentType = "image/jpeg",
                        allowOverwrite = false,
                        onProgress = {},
                    ).getOrThrow()
                    val coverKey = coverUpload.Key ?: coverName
                    uploadedObjects += "thumbnails" to coverKey
                    storageService.getPublicUrl("thumbnails", coverKey)
                        .takeIf(String::isNotBlank)
                        ?: error("COMMUNITY_MUSIC_COVER_PUBLIC_URL_REQUIRED")
                }
                onProgress(0.80f)
                val terms = communityApi.terms(token).getOrThrow()
                onProgress(0.84f)
                val created = communityApi.create(
                    token,
                    CreateCommunityMusicRequest(
                        title = draft.title.trim().take(140),
                        artist = draft.artist.trim().take(120),
                        audioUrl = audioUrl,
                        durationMs = durationMs,
                        album = draft.album.trim().takeIf(String::isNotBlank),
                        collaborators = draft.collaborators.map(String::trim).filter(String::isNotBlank).distinct().take(12),
                        coverUrl = coverUrl,
                        genre = draft.genre.trim().takeIf(String::isNotBlank),
                        subgenre = draft.subgenre.trim().takeIf(String::isNotBlank),
                        mood = draft.mood.trim().takeIf(String::isNotBlank),
                        tags = draft.tags.map(String::trim).filter(String::isNotBlank).distinct().take(20),
                        language = draft.language.trim().takeIf(String::isNotBlank),
                        instrumental = draft.instrumental,
                        explicit = draft.explicit,
                        year = draft.year,
                        description = draft.description.trim().takeIf(String::isNotBlank),
                        lyrics = draft.lyrics.trim().takeIf(String::isNotBlank),
                        rightsType = draft.rightsType,
                        licenseName = when (draft.rightsType) {
                            "CC0" -> "CC0 1.0"
                            "PUBLIC_DOMAIN" -> "Public Domain"
                            else -> "Derechos controlados por el autor"
                        },
                        licenseUrl = draft.licenseUrl.trim().takeIf(String::isNotBlank),
                        originalSourceUrl = draft.originalSourceUrl.trim().takeIf(String::isNotBlank),
                        territories = draft.territories.map(String::trim).filter(String::isNotBlank).distinct().take(80),
                        attribution = "${draft.title.trim()} — ${draft.artist.trim()}",
                        visibility = draft.visibility,
                        allowReuse = draft.allowReuse,
                        allowRemix = draft.allowRemix,
                        termsVersion = terms.version,
                        declarations = CommunityMusicDeclarations(true, true, true, true),
                        evidence = draft.evidenceDescription.trim().takeIf(String::isNotBlank)
                            ?.let { mapOf("description" to it.take(2_000)) }
                            .orEmpty(),
                    ),
                ).getOrThrow()
                serverTrackCreated = true
                onProgress(0.94f)
                val published = communityApi.submitForReview(token, created.id).getOrThrow()
                require(published.moderationState == "APPROVED") {
                    "La validación terminó, pero el servidor no confirmó la publicación."
                }
                onProgress(1f)
                published
            }
        }
        if (result.isFailure && !serverTrackCreated) {
            cleanupUncommittedCommunityMusicUploads(uploadedObjects) { bucket, key ->
                storageService.deleteFile(bucket, key)
            }
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        result
    }

    private fun parseApprovedTrack(page: JsonObject): ApprovedMomentMusic? {
        val info = page["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val mime = info["mime"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        if (!isPlayableMomentMusicMime(mime)) return null

        val metadata = info["extmetadata"]?.jsonObject ?: return null
        fun metadataText(key: String): String? = metadata[key]?.jsonObject
            ?.get("value")?.jsonPrimitive?.contentOrNull
            ?.let(::plainText)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val license = approvedMomentMusicLicenseOrNull(
            metadataText("LicenseShortName") ?: metadataText("UsageTerms"),
        )
            ?: return null
        val audioUrl = info["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val sourcePage = info["descriptionurl"]?.jsonPrimitive?.contentOrNull
            ?: page["canonicalurl"]?.jsonPrimitive?.contentOrNull
            ?: return null
        if (!audioUrl.isApprovedCommonsAssetUrl() || !sourcePage.isApprovedCommonsPageUrl()) return null

        val pageId = page["pageid"]?.jsonPrimitive?.contentOrNull ?: return null
        val rawTitle = page["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val title = rawTitle.removePrefix("File:").substringBeforeLast('.').trim().ifBlank { "Audio de Commons" }
        val durationMs = ((info["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1_000.0)
            .toLong()
            .coerceAtLeast(1_000L)
        if (durationMs < 5_000L) return null
        val categories = metadataText("Categories")
            ?.split('|')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.take(8)
            .orEmpty()
        return ApprovedMomentMusic(
            id = "commons:$pageId",
            title = title.take(140),
            artist = (metadataText("Artist") ?: metadataText("Credit") ?: "Autor no indicado").take(120),
            durationMs = durationMs,
            audioUrl = audioUrl,
            sourcePageUrl = sourcePage,
            license = license,
            licenseUrl = metadataText("LicenseUrl")?.takeIf { it.startsWith("https://") },
            artworkUrl = info["thumburl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") },
            categories = categories,
        )
    }

    private fun plainText(value: String): String = Jsoup.parse(value).text()

    companion object {
        private const val COMMONS_API = "https://commons.wikimedia.org/w/api.php"
        private const val USER_AGENT = "Novvin-Android/3.3.0 (Moment music catalog; support@novvin.app)"
    }

    private fun parseCombinedCursor(cursor: String?): Pair<String?, String?> {
        if (cursor.isNullOrBlank()) return null to null
        val parts = cursor.split('|', limit = 2)
        fun decoded(value: String?): String? = value?.takeUnless { it == "-" || it.isBlank() }
        return decoded(parts.getOrNull(0)) to decoded(parts.getOrNull(1))
    }

    private fun combinedCursor(commons: String?, community: String?): String? {
        if (commons.isNullOrBlank() && community.isNullOrBlank()) return null
        return "${commons ?: "-"}|${community ?: "-"}"
    }
}

internal fun isApprovedMomentMusicTrack(track: ApprovedMomentMusic): Boolean =
    when (track.source) {
        ApprovedMomentMusic.SOURCE_WIKIMEDIA_COMMONS ->
            track.audioUrl.isApprovedCommonsAssetUrl() &&
                track.sourcePageUrl.isApprovedCommonsPageUrl() &&
                approvedMomentMusicLicenseOrNull(track.license) != null
        "novvin_community" ->
            track.id.startsWith("community:") &&
                track.audioUrl.startsWith("https://") &&
                track.durationMs >= 5_000L &&
                track.allowUseInMoments
        "openverse", "jamendo" ->
            track.id.startsWith("${track.source}:") &&
                track.audioUrl.startsWith("https://") &&
                track.sourcePageUrl.startsWith("https://") &&
                approvedMomentMusicLicenseOrNull(track.license) != null &&
                track.allowUseInMoments &&
                track.durationMs >= 5_000L
        else -> false
    }

internal fun approvedMomentMusicLicenseOrNull(value: String?): String? {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return when {
        normalized == "cc0" || normalized.startsWith("cc0 ") ||
            normalized.contains("creative commons zero") -> "CC0 1.0"
        normalized == "public domain" || normalized == "dominio público" ||
            normalized.startsWith("public domain ") -> "Public Domain"
        else -> null
    }
}

internal fun isPlayableMomentMusicMime(value: String?): Boolean = when (value?.trim()?.lowercase()) {
    "audio/mpeg",
    "audio/mp4",
    "audio/aac",
    "audio/ogg",
    "application/ogg",
    "audio/opus",
    "audio/flac",
    "audio/x-flac",
    "audio/wav",
    "audio/x-wav",
    "audio/webm" -> true
    else -> false
}

private const val COMMUNITY_MUSIC_MAX_BYTES = 80L * 1024L * 1024L

internal fun canonicalCommunityMusicMime(reportedType: String?, displayName: String): String? {
    val normalized = reportedType?.trim()?.lowercase().orEmpty()
    val canonicalReported = when (normalized) {
        "audio/x-m4a", "audio/m4a" -> "audio/mp4"
        "audio/x-aac" -> "audio/aac"
        "audio/vnd.wave" -> "audio/wav"
        "application/ogg" -> "audio/ogg"
        "audio/x-flac" -> "audio/flac"
        else -> normalized
    }
    if (isPlayableMomentMusicMime(canonicalReported)) return canonicalReported
    return when (displayName.substringAfterLast('.', "").trim().lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav", "wave" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "webm", "weba" -> "audio/webm"
        else -> null
    }
}

internal fun communityMusicExtension(contentType: String): String = when (contentType.lowercase()) {
    "audio/mpeg" -> "mp3"
    "audio/mp4", "audio/aac" -> "m4a"
    "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/webm" -> "webm"
    else -> "wav"
}

internal fun canonicalCommunityCoverMime(reportedType: String?, displayName: String): String? {
    val normalized = when (reportedType?.trim()?.lowercase().orEmpty()) {
        "image/jpg", "image/pjpeg" -> "image/jpeg"
        else -> reportedType?.trim()?.lowercase().orEmpty()
    }
    if (normalized in setOf("image/jpeg", "image/png", "image/webp")) return normalized
    return when (displayName.substringAfterLast('.', "").trim().lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> null
    }
}

internal fun resolveCommunityDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()?.takeIf(String::isNotBlank)
    ?: if (uri.scheme == "file") java.io.File(uri.path.orEmpty()).name else uri.lastPathSegment.orEmpty()

private fun resolveCommunityMusicSize(context: Context, uri: Uri, maxBytes: Long): Long {
    val resolver = context.contentResolver
    val descriptorSize = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()?.takeIf { it > 0L }
    if (descriptorSize != null) return descriptorSize
    val cursorSize = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()?.takeIf { it > 0L }
    if (cursorSize != null) return cursorSize

    return resolver.openInputStream(uri)?.use { stream ->
        val buffer = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return@use total
        }
        total
    } ?: 0L
}

private fun resolveCommunityMusicDuration(context: Context, uri: Uri): Long {
    return MediaMetadataRetriever().use { retriever ->
        runCatching { retriever.setDataSource(context, uri) }.recoverCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > 0L) {
                    retriever.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                } else {
                    retriever.setDataSource(descriptor.fileDescriptor)
                }
            } ?: throw it
        }.getOrThrow()
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    }
}

fun String.isApprovedCommonsAssetUrl(): Boolean = runCatching {
    val uri = java.net.URI(trim())
    uri.scheme.equals("https", true) && uri.host.equals("upload.wikimedia.org", true)
}.getOrDefault(false)

fun String.isApprovedCommonsPageUrl(): Boolean = runCatching {
    val uri = java.net.URI(trim())
    uri.scheme.equals("https", true) && uri.host.equals("commons.wikimedia.org", true)
}.getOrDefault(false)
