package conectate.com.core.media

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

internal data class ProfileMediaMutation(
    val avatarUrl: String? = null,
    val coverUrl: String? = null,
    val removeAvatar: Boolean = false,
    val removeCover: Boolean = false
) {
    val changesAvatar: Boolean
        get() = removeAvatar || avatarUrl != null

    val changesCover: Boolean
        get() = removeCover || coverUrl != null
}

internal enum class ProfileMediaField {
    AVATAR,
    COVER
}

internal data class ProfileMediaConfirmationFailure(
    val field: ProfileMediaField,
    val expectedUrl: String?,
    val actualUrl: String?,
    val removalExpected: Boolean
)

/**
 * Pure profile-media transaction rules shared by the repository and its unit tests.
 */
internal object ProfileMediaSync {
    private const val PUBLIC_OBJECT_MARKER = "/storage/v1/object/public/"

    fun confirmationFailure(
        mutation: ProfileMediaMutation,
        actualAvatarUrl: String?,
        actualCoverUrl: String?
    ): ProfileMediaConfirmationFailure? {
        if (mutation.removeAvatar && !isAbsent(actualAvatarUrl)) {
            return ProfileMediaConfirmationFailure(
                field = ProfileMediaField.AVATAR,
                expectedUrl = null,
                actualUrl = actualAvatarUrl,
                removalExpected = true
            )
        }
        if (
            !mutation.removeAvatar &&
            mutation.avatarUrl != null &&
            !sameReference(mutation.avatarUrl, actualAvatarUrl)
        ) {
            return ProfileMediaConfirmationFailure(
                field = ProfileMediaField.AVATAR,
                expectedUrl = mutation.avatarUrl,
                actualUrl = actualAvatarUrl,
                removalExpected = false
            )
        }
        if (mutation.removeCover && !isAbsent(actualCoverUrl)) {
            return ProfileMediaConfirmationFailure(
                field = ProfileMediaField.COVER,
                expectedUrl = null,
                actualUrl = actualCoverUrl,
                removalExpected = true
            )
        }
        if (
            !mutation.removeCover &&
            mutation.coverUrl != null &&
            !sameReference(mutation.coverUrl, actualCoverUrl)
        ) {
            return ProfileMediaConfirmationFailure(
                field = ProfileMediaField.COVER,
                expectedUrl = mutation.coverUrl,
                actualUrl = actualCoverUrl,
                removalExpected = false
            )
        }
        return null
    }

    fun replacedUrls(
        mutation: ProfileMediaMutation,
        previousAvatarUrl: String?,
        previousCoverUrl: String?,
        confirmedAvatarUrl: String?,
        confirmedCoverUrl: String?
    ): List<String> {
        val candidates = buildList {
            if (
                mutation.changesAvatar &&
                !isAbsent(previousAvatarUrl) &&
                !sameReference(previousAvatarUrl, confirmedAvatarUrl)
            ) {
                add(previousAvatarUrl!!.trim())
            }
            if (
                mutation.changesCover &&
                !isAbsent(previousCoverUrl) &&
                !sameReference(previousCoverUrl, confirmedCoverUrl)
            ) {
                add(previousCoverUrl!!.trim())
            }
        }

        return candidates.distinctBy { canonicalReference(it) ?: it }
    }

    fun sameReference(first: String?, second: String?): Boolean {
        val firstCanonical = canonicalReference(first) ?: return false
        return firstCanonical == canonicalReference(second)
    }

    fun confirmedReference(uploadedUrl: String?, persistedUrl: String?): String? {
        return persistedUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: uploadedUrl
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    fun uploadedObjectPath(
        bucket: String,
        requestedPath: String,
        responseKey: String?,
        responseName: String?
    ): String {
        val serverValue = sequenceOf(responseKey, responseName)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: return requestedPath
        if (serverValue.startsWith("http", ignoreCase = true)) return serverValue

        val normalized = serverValue.replace("\\", "/").trimStart('/')
        return normalized.removePrefix("${bucket.trim()}/").ifBlank { requestedPath }
    }

    internal fun canonicalReference(value: String?): String? {
        val raw = value
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: return null
        val normalized = raw.replace("\\", "/")
        val withoutVersion = normalized.substringBefore('#').substringBefore('?').trimEnd('/')
        if (withoutVersion.isBlank()) return null

        val markerIndex = withoutVersion.indexOf(PUBLIC_OBJECT_MARKER, ignoreCase = true)
        if (markerIndex >= 0) {
            val storageReference = withoutVersion.substring(markerIndex + PUBLIC_OBJECT_MARKER.length)
            return decodeStorageReference(storageReference)?.let { "storage:$it" }
        }

        val relativeReference = withoutVersion.removePrefix("/")
        if (!relativeReference.contains("://") && relativeReference.count { it == '/' } >= 1) {
            return decodeStorageReference(relativeReference)?.let { "storage:$it" }
        }

        return withoutVersion
    }

    private fun decodeStorageReference(value: String): String? {
        val segments = value.split('/').filter(String::isNotBlank)
        if (segments.size < 2) return null
        return runCatching {
            segments.joinToString("/") { segment ->
                URLDecoder.decode(segment, StandardCharsets.UTF_8.name())
            }
        }.getOrNull()
    }

    private fun isAbsent(value: String?): Boolean =
        value.isNullOrBlank() || value.trim().equals("null", ignoreCase = true)
}

/** Tracks whether a remote read began before the latest committed profile mutation. */
internal class ProfileMutationClock {
    private val generation = AtomicLong(0L)

    fun snapshot(): Long = generation.get()

    fun markCommitted(): Long = generation.incrementAndGet()

    fun isCurrent(snapshot: Long): Boolean = snapshot == generation.get()
}
