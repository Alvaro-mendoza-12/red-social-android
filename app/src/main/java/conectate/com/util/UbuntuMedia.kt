package conectate.com.util

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.media3.common.MediaItem
import conectate.com.data.localfallback.UbuntuStorageUrl

object UbuntuMedia {
    fun resolveUrl(url: String?): String? = UbuntuStorageUrl.rewrite(url)

    fun mediaItem(url: String): MediaItem =
        MediaItem.fromUri(resolveUrl(url) ?: url)

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
