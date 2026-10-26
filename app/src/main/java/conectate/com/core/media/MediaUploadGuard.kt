package conectate.com.core.media

import javax.inject.Inject
import javax.inject.Singleton

data class MediaValidationResult(
    val allowed: Boolean,
    val reason: String? = null
)

@Singleton
class MediaUploadGuard @Inject constructor() {

    private val messagingBuckets = setOf("chat-media", "group-media", "community-media")
    private val blockedMessageAttachmentExtensions = setOf(
        "exe", "dll", "com", "scr", "msi", "bat", "cmd", "ps1", "psm1", "sh", "bash",
        "zsh", "fish", "vbs", "vbe", "js", "mjs", "cjs", "hta", "html", "htm", "svg",
        "wasm", "class", "so", "dylib", "elf"
    )
    private val blockedMessageAttachmentMimes = setOf(
        "text/html", "application/xhtml+xml", "image/svg+xml", "text/javascript",
        "application/javascript", "application/x-javascript", "application/wasm",
        "application/x-msdownload", "application/x-executable", "application/x-sh",
        "application/x-httpd-php"
    )
    private val signatureRequiredMessageExtensions = setOf(
        "pdf", "zip", "doc", "docx", "docm", "xls", "xlsx", "xlsm", "xlsb", "ppt", "pptx", "pptm",
        "odt", "ods", "odp", "epub", "rtf"
    )

    private data class BucketPolicy(
        val maxBytes: Long,
        val allowedContentTypes: Set<String>,
        val allowedExtensions: Set<String> = emptySet()
    )

    private val policies = mapOf(
        "post-media" to BucketPolicy(
            maxBytes = 25L * 1024 * 1024,
            allowedContentTypes = setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "video/mp4"
            ),
            allowedExtensions = setOf("jpg", "jpeg", "png", "webp", "mp4", "mov")
        ),
        "chat-media" to BucketPolicy(
            maxBytes = 60L * 1024 * 1024,
            allowedContentTypes = setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/gif",
                "video/mp4",
                "video/webm",
                "audio/mpeg",
                "audio/mp4",
                "audio/wav",
                "audio/ogg",
                "audio/webm",
                "application/pdf",
                "application/zip",
                "application/vnd.ms-excel",
                "application/msword",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain",
                "text/csv",
                "application/octet-stream"
            ),
            allowedExtensions = setOf(
                "jpg", "jpeg", "png", "webp", "gif", "mp4", "mov", "webm",
                "mp3", "m4a", "wav", "ogg", "pdf", "zip", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv"
            )
        ),
        "group-media" to BucketPolicy(
            maxBytes = 60L * 1024 * 1024,
            allowedContentTypes = setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/gif",
                "video/mp4",
                "video/webm",
                "audio/mpeg",
                "audio/mp4",
                "audio/wav",
                "audio/ogg",
                "audio/webm",
                "application/pdf",
                "application/zip",
                "application/vnd.ms-excel",
                "application/msword",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain",
                "text/csv",
                "application/octet-stream"
            ),
            allowedExtensions = setOf(
                "jpg", "jpeg", "png", "webp", "gif", "mp4", "mov", "webm",
                "mp3", "m4a", "wav", "ogg", "pdf", "zip", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv"
            )
        ),
        "community-media" to BucketPolicy(
            maxBytes = 60L * 1024 * 1024,
            allowedContentTypes = setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/gif",
                "video/mp4",
                "video/webm",
                "audio/mpeg",
                "audio/mp4",
                "audio/wav",
                "audio/ogg",
                "audio/webm",
                "application/pdf",
                "application/zip",
                "application/vnd.ms-excel",
                "application/msword",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain",
                "text/csv",
                "application/octet-stream"
            ),
            allowedExtensions = setOf(
                "jpg", "jpeg", "png", "webp", "gif", "mp4", "mov", "webm",
                "mp3", "m4a", "wav", "ogg", "pdf", "zip", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv"
            )
        ),
        "avatars" to BucketPolicy(
            maxBytes = 50L * 1024 * 1024,
            allowedContentTypes = setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/gif",
                "video/mp4"
            ),
            allowedExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "mp4")
        ),
        "report-evidence" to BucketPolicy(
            maxBytes = 10L * 1024 * 1024,
            allowedContentTypes = setOf("image/jpeg", "image/png", "image/webp"),
            allowedExtensions = setOf("jpg", "jpeg", "png", "webp")
        ),
        "groups" to BucketPolicy(
            maxBytes = 60L * 1024 * 1024,
            allowedContentTypes = setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "video/mp4",
                "audio/mpeg",
                "audio/mp4",
                "audio/wav"
            ),
            allowedExtensions = setOf("jpg", "jpeg", "png", "webp", "mp4", "mov", "mp3", "m4a", "wav")
        ),
        "communities" to BucketPolicy(
            maxBytes = 60L * 1024 * 1024,
            allowedContentTypes = setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "video/mp4",
                "audio/mpeg",
                "audio/mp4",
                "audio/wav"
            ),
            allowedExtensions = setOf("jpg", "jpeg", "png", "webp", "mp4", "mov", "mp3", "m4a", "wav")
        ),
        "group-audio" to BucketPolicy(
            maxBytes = 20L * 1024 * 1024,
            allowedContentTypes = setOf("audio/mpeg", "audio/mp4", "audio/wav"),
            allowedExtensions = setOf("mp3", "m4a", "wav")
        ),
        "profile-audio" to BucketPolicy(
            maxBytes = 40L * 1024 * 1024,
            allowedContentTypes = setOf(
                "audio/mpeg",
                "audio/mp4",
                "audio/wav",
                "audio/ogg",
                "audio/webm",
                "audio/flac"
            ),
            allowedExtensions = setOf("mp3", "m4a", "wav", "ogg", "webm", "flac")
        ),
        "community-music" to BucketPolicy(
            maxBytes = 80L * 1024L * 1024L,
            allowedContentTypes = setOf(
                "audio/mpeg",
                "audio/mp4",
                "audio/aac",
                "audio/wav",
                "audio/x-wav",
                "audio/ogg",
                "application/ogg",
                "audio/opus",
                "audio/webm",
                "audio/flac",
                "audio/x-flac"
            ),
            allowedExtensions = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "webm", "flac")
        ),
        "moment-media" to BucketPolicy(
            maxBytes = 35L * 1024 * 1024,
            allowedContentTypes = setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "video/mp4"
            ),
            allowedExtensions = setOf("jpg", "jpeg", "png", "webp", "mp4", "mov")
        ),
        "post-comments-media" to BucketPolicy(
            maxBytes = 20L * 1024 * 1024,
            allowedContentTypes = setOf("audio/mp4", "audio/mpeg", "audio/wav"),
            allowedExtensions = setOf("m4a", "mp3", "wav")
        ),
        "video-comments-media" to BucketPolicy(
            maxBytes = 20L * 1024 * 1024,
            allowedContentTypes = setOf("audio/mp4", "audio/mpeg", "audio/wav"),
            allowedExtensions = setOf("m4a", "mp3", "wav")
        ),
        "videos" to BucketPolicy(
            maxBytes = 512L * 1024 * 1024,
            allowedContentTypes = setOf("video/mp4"),
            allowedExtensions = setOf("mp4", "mov")
        ),
        "live-replays" to BucketPolicy(
            maxBytes = 512L * 1024 * 1024,
            allowedContentTypes = setOf("video/mp4"),
            allowedExtensions = setOf("mp4", "mov")
        ),
        "thumbnails" to BucketPolicy(
            maxBytes = 10L * 1024 * 1024,
            allowedContentTypes = setOf("image/jpeg", "image/png", "image/webp"),
            allowedExtensions = setOf("jpg", "jpeg", "png", "webp")
        ),
        "challenges" to BucketPolicy(
            maxBytes = 30L * 1024 * 1024,
            allowedContentTypes = setOf(
                "image/jpeg",
                "image/png",
                "image/webp",
                "video/mp4",
                "audio/mpeg",
                "audio/mp4",
                "audio/wav"
            ),
            allowedExtensions = setOf("jpg", "jpeg", "png", "webp", "mp4", "mov", "mp3", "m4a", "wav")
        )
    )

    fun validate(
        bucketName: String,
        fileName: String,
        bytes: ByteArray,
        contentType: String
    ): MediaValidationResult = validateMetadata(
        bucketName = bucketName,
        fileName = fileName,
        sizeBytes = bytes.size.toLong(),
        contentType = contentType,
        headerBytes = bytes
    )

    fun validateMetadata(
        bucketName: String,
        fileName: String,
        sizeBytes: Long,
        contentType: String,
        headerBytes: ByteArray
    ): MediaValidationResult {
        if (fileName.isBlank()) {
            return MediaValidationResult(false, "Invalid file name")
        }
        if (fileName.contains("..") || fileName.contains("\\") || fileName.startsWith("/") || fileName.contains('\u0000')) {
            return MediaValidationResult(false, "Unsafe file path")
        }
        if (sizeBytes <= 0L || headerBytes.isEmpty()) {
            return MediaValidationResult(false, "Empty file payload")
        }

        val cleanContentType = contentType.trim().lowercase()
        if (cleanContentType.isBlank()) {
            return MediaValidationResult(false, "Missing content type")
        }
        val canonicalType = conectate.com.util.canonicalizeMediaContentType(cleanContentType)
            ?: cleanContentType.substringBefore(";")
        val fileExtension = fileName.substringAfterLast('.', "").lowercase()
        val hasSafeAttachmentExtension = fileExtension.matches(Regex("[a-z0-9]{1,16}")) &&
            fileExtension !in blockedMessageAttachmentExtensions
        val isSafeMessagingAttachment = bucketName in messagingBuckets && hasSafeAttachmentExtension
        val isSafeOpaqueAttachmentMime = (
            canonicalType.startsWith("application/") || canonicalType.startsWith("text/")
            ) && canonicalType !in blockedMessageAttachmentMimes
        val isZipBasedOpaqueDocument = fileExtension in setOf(
            "zip", "odt", "ods", "odp", "epub", "pages", "numbers", "key", "xls"
        )
        val expectedFileMime = runCatching {
            conectate.com.util.guessMimeTypeFromFileName(fileName)
                ?.let { conectate.com.util.canonicalizeMediaContentType(it) }
        }.getOrNull()
        val isGenericMime = canonicalType in setOf("application/octet-stream", "binary/octet-stream")

        val policy = policies[bucketName]
        if (policy != null) {
            if (sizeBytes > policy.maxBytes) {
                return MediaValidationResult(
                    allowed = false,
                    reason = "File too large for $bucketName (max ${policy.maxBytes} bytes)"
                )
            }

            val inferredType = inferMimeType(headerBytes, fileExtension)
            val effectiveType = if (canonicalType == "application/octet-stream" && inferredType != null) {
                inferredType
            } else {
                canonicalType
            }

            val genericDocumentMime = isSafeMessagingAttachment && (
                isGenericMime || isSafeOpaqueAttachmentMime ||
                    (isZipBasedOpaqueDocument && inferredType == "application/zip" &&
                        canonicalType.startsWith("application/"))
                )
            if (policy.allowedContentTypes.isNotEmpty() &&
                !policy.allowedContentTypes.contains(effectiveType) && !genericDocumentMime
            ) {
                return MediaValidationResult(
                    allowed = false,
                    reason = "Unsupported content type $canonicalType for $bucketName"
                )
            }
            if (policy.allowedExtensions.isNotEmpty()) {
                if (fileExtension.isBlank() ||
                    (!policy.allowedExtensions.contains(fileExtension) && !isSafeMessagingAttachment)
                ) {
                    return MediaValidationResult(
                        allowed = false,
                        reason = "Unsupported file extension .$fileExtension for $bucketName"
                    )
                }
            }
        } else {
            if (sizeBytes > (80L * 1024 * 1024)) {
                return MediaValidationResult(false, "File exceeds global upload limit")
            }
        }

        val inferredType = inferMimeType(headerBytes, fileExtension)
        if (bucketName in messagingBuckets && fileExtension in signatureRequiredMessageExtensions &&
            inferredType.isNullOrBlank()
        ) {
            return MediaValidationResult(false, "Could not verify document signature")
        }
        val compatibleOpaqueArchive = isSafeMessagingAttachment && isZipBasedOpaqueDocument &&
            inferredType == "application/zip" && canonicalType.startsWith("application/")
        val extensionSignatureMismatch = !inferredType.isNullOrBlank() &&
            !expectedFileMime.isNullOrBlank() &&
            !isCompatibleMime(expectedFileMime, inferredType) &&
            !(isZipBasedOpaqueDocument && inferredType == "application/zip")
        if (extensionSignatureMismatch || (!inferredType.isNullOrBlank() &&
                !isCompatibleMime(canonicalType, inferredType) && !compatibleOpaqueArchive)
        ) {
            return MediaValidationResult(
                allowed = false,
                reason = "File signature does not match declared content type"
            )
        }

        if (containsExecutableHeader(headerBytes)) {
            return MediaValidationResult(false, "Executable payload is not allowed")
        }

        return MediaValidationResult(true)
    }

    private fun inferMimeType(bytes: ByteArray, extension: String): String? {
        if (bytes.size < 4) return null

        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (
            bytes[0] == 0x47.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte()
        ) {
            return "image/gif"
        }
        if (
            bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()
        ) {
            return "application/pdf"
        }
        if (
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte()) &&
            (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte())
        ) {
            return when (extension) {
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "xlsm" -> "application/vnd.ms-excel.sheet.macroenabled.12"
                "xlsb" -> "application/vnd.ms-excel.sheet.binary.macroenabled.12"
                "docm" -> "application/vnd.ms-word.document.macroenabled.12"
                "pptm" -> "application/vnd.ms-powerpoint.presentation.macroenabled.12"
                else -> "application/zip"
            }
        }
        if (
            bytes.size >= 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte() && bytes[4] == 0xA1.toByte() &&
            bytes[5] == 0xB1.toByte() && bytes[6] == 0x1A.toByte() && bytes[7] == 0xE1.toByte()
        ) {
            return when (extension) {
                "xls" -> "application/vnd.ms-excel"
                "doc" -> "application/msword"
                "ppt" -> "application/vnd.ms-powerpoint"
                else -> "application/x-ole-storage"
            }
        }
        if (extension == "rtf" && bytes.size >= 5 && bytes.copyOfRange(0, 5).decodeToString() == "{\\rtf") {
            return "application/rtf"
        }
        if (
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes.getOrNull(8) == 0x57.toByte() &&
            bytes.getOrNull(9) == 0x45.toByte() &&
            bytes.getOrNull(10) == 0x42.toByte() &&
            bytes.getOrNull(11) == 0x50.toByte()
        ) {
            return "image/webp"
        }
        if (
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes.getOrNull(8) == 0x57.toByte() &&
            bytes.getOrNull(9) == 0x41.toByte() &&
            bytes.getOrNull(10) == 0x56.toByte() &&
            bytes.getOrNull(11) == 0x45.toByte()
        ) {
            return "audio/wav"
        }
        if ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xF6) == 0xF0) {
            return "audio/aac"
        }
        if (
            bytes[0] == 0x49.toByte() &&
            bytes[1] == 0x44.toByte() &&
            bytes[2] == 0x33.toByte()
        ) {
            return "audio/mpeg"
        }
        if ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0) {
            return "audio/mpeg"
        }
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).decodeToString() == "OggS") {
            return "audio/ogg"
        }
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).decodeToString() == "fLaC") {
            return "audio/flac"
        }
        if (
            bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        ) {
            return "video/webm"
        }
        if (
            bytes.size >= 12 && bytes.getOrNull(4) == 0x66.toByte() &&
            bytes.getOrNull(5) == 0x74.toByte() &&
            bytes.getOrNull(6) == 0x79.toByte() &&
            bytes.getOrNull(7) == 0x70.toByte()
        ) {
            val brand = bytes.copyOfRange(8, 12).decodeToString().lowercase()
            return if (brand.contains("m4a")) "audio/mp4" else "video/mp4"
        }
        if (extension in setOf("txt", "csv", "tsv")) {
            val sample = bytes.take(512)
            val invalidControls = sample.count { value ->
                val unsigned = value.toInt() and 0xFF
                unsigned == 0 || unsigned < 9 || (unsigned in 14..31)
            }
            if (invalidControls <= maxOf(1, sample.size / 50)) {
                return when (extension) {
                    "csv" -> "text/csv"
                    "tsv" -> "text/tab-separated-values"
                    else -> "text/plain"
                }
            }
        }
        return null
    }

    private fun isCompatibleMime(declared: String, inferred: String): Boolean {
        if (declared == inferred) return true
        if (declared in setOf("audio/ogg", "application/ogg", "audio/opus") &&
            inferred in setOf("audio/ogg", "application/ogg", "audio/opus")) return true
        return (declared == "audio/mp4" && inferred == "video/mp4") ||
            (declared == "video/mp4" && inferred == "audio/mp4") ||
            (declared == "audio/webm" && inferred == "video/webm") ||
            (declared == "application/octet-stream" && inferred.isNotBlank())
    }

    private fun containsExecutableHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // MZ (PE), ELF, shebang.
        val isMZ = bytes[0] == 0x4D.toByte() && bytes[1] == 0x5A.toByte()
        val isElf = bytes[0] == 0x7F.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0x4C.toByte() && bytes[3] == 0x46.toByte()
        val isShebang = bytes[0] == 0x23.toByte() && bytes[1] == 0x21.toByte()
        return isMZ || isElf || isShebang
    }
}


