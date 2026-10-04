package conectate.com.util

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import conectate.com.BuildConfig
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.localfallback.UbuntuStorageUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single network policy for every Media3 streaming surface.
 *
 * Community music remains streaming-only: this factory does not create a
 * download service or a persistent offline catalog. VideoCache may wrap the
 * HTTP factory with its bounded, evictable playback cache.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object MediaPlaybackDataSources {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
    const val NETWORK_STACK = "okhttp-http2"

    // One process-wide client lets the visible player and bounded preloader
    // reuse TLS sessions, sockets and HTTP/2 connections instead of opening a
    // device-dependent HttpURLConnection for every range request.
    private val mediaHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(PublicBackendDns)
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // Never let an HTTPS media URL downgrade to cleartext through a
            // redirect. Same-protocol redirects remain supported.
            .followSslRedirects(false)
            .apply {
                if (BuildConfig.DEBUG) eventListenerFactory { MediaNetworkTrace() }
            }
            .build()
    }

    fun userAgent(versionName: String = BuildConfig.VERSION_NAME): String =
        "NOVVIN/$versionName (Android)"

    /** Native audio caches must use the same public DNS and pooled TLS policy. */
    internal fun networkClient(): OkHttpClient = mediaHttpClient

    fun httpFactory(includeStorageHeaders: Boolean = true): HttpDataSource.Factory {
        val factory = OkHttpDataSource.Factory(mediaHttpClient)
            .setUserAgent(userAgent())

        if (includeStorageHeaders) {
            factory.setDefaultRequestProperties(UbuntuStorageUrl.publicRequestHeaders)
        }
        return factory
    }

    fun streamingFactory(
        context: Context,
        includeStorageHeaders: Boolean = true,
    ): DataSource.Factory = DefaultDataSource.Factory(
        context.applicationContext,
        httpFactory(includeStorageHeaders),
    )

    /** Reuse the initial HTTP response for a short, immediate audio trim seek. */
    internal fun interactiveAudioFactory(context: Context): DataSource.Factory =
        DefaultDataSource.Factory(context.applicationContext, DataSource.Factory {
            ShortAudioSeekDataSource(httpFactory().createDataSource())
        })
}
