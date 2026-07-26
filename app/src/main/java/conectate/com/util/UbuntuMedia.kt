package conectate.com.util

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.media3.common.MediaItem
import conectate.com.data.localfallback.UbuntuStorageUrl

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object UbuntuMedia {
    fun resolveUrl(url: String?): String? = UbuntuStorageUrl.rewrite(url)

    fun mediaItem(url: String): MediaItem {
        val resolvedUrl = resolveUrl(url) ?: url
        return MediaItem.Builder()
            .setUri(resolvedUrl)
            // Las URLs firmadas o reescritas pueden cambiar aunque el archivo
            // sea el mismo. Una clave estable permite reutilizar los bytes ya
            // descargados al abrir el video otra vez.
            .setCustomCacheKey(UbuntuStorageUrl.stableCacheKey(resolvedUrl))
            .build()
    }

    fun setDataSource(mediaPlayer: MediaPlayer, url: String) {
        val resolvedUrl = resolveUrl(url) ?: url
        mediaPlayer.setDataSource(resolvedUrl)
    }

    fun setDataSource(context: Context, mediaPlayer: MediaPlayer, url: String) {
        val resolvedUrl = resolveUrl(url) ?: url
        if (resolvedUrl.startsWith("http", ignoreCase = true) &&
            UbuntuStorageUrl.publicRequestHeaders.isNotEmpty()
        ) {
            mediaPlayer.setDataSource(context, Uri.parse(resolvedUrl), UbuntuStorageUrl.publicRequestHeaders)
        } else {
            mediaPlayer.setDataSource(context, Uri.parse(resolvedUrl))
        }
    }
}
