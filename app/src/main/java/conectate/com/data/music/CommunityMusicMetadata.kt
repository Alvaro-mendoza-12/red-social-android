package conectate.com.data.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class CommunityMusicMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkFile: File?,
)

/** Runs on IO. Keeps embedded artwork from the original even after AAC cropping. */
internal fun readCommunityMusicMetadata(
    context: Context,
    audioUri: Uri,
    sourceUri: Uri = audioUri,
    albumArtUri: Uri? = null,
): CommunityMusicMetadata {
    var title = ""
    var artist = ""
    var album = ""
    var embedded: ByteArray? = null
    runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, sourceUri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            embedded = retriever.embeddedPicture?.takeIf { it.size <= MAX_ARTWORK_BYTES }
        }
    }
    val duration = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, audioUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)
    val cover = embedded?.let { prepareCommunityMusicArtwork(context, it) }
        ?: albumArtUri?.let { uri ->
            runCatching { prepareCommunityMusicCoverUpload(context, uri) }.getOrNull()
        }
    return CommunityMusicMetadata(title, artist.takeUnless { it.equals("<unknown>", true) }.orEmpty(), album, duration, cover)
}

private const val MAX_ARTWORK_BYTES = 10 * 1024 * 1024

/** Preflight must succeed before the first transfer, and the copy has one owner. */
internal suspend fun <T> withPreparedCommunityCoverUpload(
    context: Context,
    uri: Uri?,
    upload: suspend (File?) -> T,
): T {
    val file = uri?.let { prepareCommunityMusicCoverUpload(context, it) }
    try {
        currentCoroutineContext().ensureActive()
        return upload(file)
    } finally {
        file?.delete()
    }
}

/**
 * Album art and app cache URIs often have neither a provider MIME type nor a
 * display name. Decode their actual bytes and produce our own validated JPEG;
 * never infer the uploaded image format from a URI suffix or provider hint.
 * The caller owns the returned temporary file and must delete it after upload.
 */
internal fun prepareCommunityMusicCoverUpload(context: Context, uri: Uri): File {
    require(uri.scheme == "content" || uri.scheme == "file") { "No se pudo abrir la portada seleccionada." }
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_ARTWORK_BYTES) { "La portada debe pesar menos de 10 MB." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("No se pudo abrir la portada seleccionada.")
    return prepareCommunityMusicArtwork(context, bytes)
        ?: error("No se pudo leer la imagen de portada. Elige una imagen válida.")
}

/** Decode bounds first; never upload arbitrary embedded bytes as an image. */
internal fun prepareCommunityMusicArtwork(context: Context, bytes: ByteArray): File? {
    if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 64_000_000L) return null
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    var output: File? = null
    return try {
        val directory = File(context.cacheDir, "community-music-artwork").apply { mkdirs() }
        val file = File.createTempFile("cover-", ".jpg", directory)
        output = file
        file.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
        require(file.length() in 1..MAX_ARTWORK_BYTES.toLong())
        file
    } catch (_: Exception) {
        output?.delete()
        null
    } finally {
        bitmap.recycle()
    }
}
