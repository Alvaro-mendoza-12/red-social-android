package conectate.com.performance

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.media.StorageReadPolicy
import conectate.com.core.media.StorageReadRequestInterceptor
import conectate.com.core.testing.MediaAuditEntryPoint
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.util.MediaPlaybackDataSources
import conectate.com.util.StorageReadHttpDataSource
import dagger.hilt.android.EntryPointAccessors
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded, opt-in transport isolation. No member write, view event or upload. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PublicMediaTransferDeviceTest {
    @Test fun compareFullAndRangePrefixesOnTheSamePublicObject(): Unit = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowLiveGuestRead") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val guest = EntryPointAccessors.fromApplication(context, MediaAuditEntryPoint::class.java).guestModeApi()
        val hadGuest = guest.hasActiveGuest()
        val preview = guest.beginMediaPreview()
        try {
            guest.ensureIdentity()
            val target = arguments.getString("expectedPublicVideoDigest")
            val url = guest.videos().asSequence().mapNotNull { UbuntuStorageUrl.rewrite(it.videoUrl) }
                .firstOrNull { StorageReadPolicy.accepts(it, UbuntuStorageUrl.baseUrl) &&
                    it.contains("/storage/v1/object/public/") && (target == null || digest(it) == target) }
                ?: error("Expected public clip unavailable")
            val samples = JSONArray()
            var allComplete = true
            val prefixDigests = mutableSetOf<String>()
            for (edge in listOf(true, false)) for ((range, http1) in listOf(false to false, true to false, false to true)) {
                val sample = readPrefix(url, edge, range, http1)
                samples.put(sample)
                allComplete = allComplete && sample.optInt("bytes") == PREFIX_BYTES
                if (sample.optInt("bytes") == PREFIX_BYTES) prefixDigests += sample.getString("prefixSha256")
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "\nPUBLIC_TRANSFER_SAMPLE $sample\n")
                })
            }
            assertTrue("Not every bounded transport read completed; retain numeric samples", allComplete)
            assertTrue("Both routes must return the same object prefix", prefixDigests.size == 1)
        } finally {
            guest.endMediaPreview(preview)
            if (!hadGuest) guest.clear()
        }
    }

    private fun readPrefix(url: String, edge: Boolean, range: Boolean, http1: Boolean): JSONObject {
        val started = SystemClock.elapsedRealtime()
        val sample = JSONObject().put("route", if (edge) "r2" else "ubuntu")
            .put("requestedRange", range).put("http1Only", http1)
            .put("dnsMs", 0).put("connectMs", 0).put("tlsMs", 0)
            .put("dnsAttempts", 0).put("dnsFailures", 0)
            .put("connectionAttempts", 0).put("connectionFailures", 0)
        val client = MediaPlaybackDataSources.networkClient().newBuilder().apply {
            interceptors().removeAll { it is StorageReadRequestInterceptor }
            addInterceptor(StorageReadRequestInterceptor(edgeOrigin = if (edge) conectate.com.BuildConfig.MEDIA_EDGE_ORIGIN else ""))
            if (http1) protocols(listOf(Protocol.HTTP_1_1))
            callTimeout(15, TimeUnit.SECONDS)
            eventListener(object : EventListener() {
                private var dnsStarted = -1L
                private var connectStarted = -1L
                private var tlsStarted = -1L

                override fun dnsStart(call: Call, domainName: String) {
                    dnsStarted = SystemClock.elapsedRealtime()
                    sample.put("dnsAttempts", sample.optInt("dnsAttempts") + 1)
                }
                private fun finishDns() {
                    if (dnsStarted >= 0) sample.put("dnsMs", sample.optLong("dnsMs") + SystemClock.elapsedRealtime() - dnsStarted)
                    dnsStarted = -1L
                }
                override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = finishDns()
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    connectStarted = SystemClock.elapsedRealtime()
                    sample.put("connectionAttempts", sample.optInt("connectionAttempts") + 1)
                }
                private fun finishConnect() {
                    if (connectStarted >= 0) sample.put("connectMs", sample.optLong("connectMs") + SystemClock.elapsedRealtime() - connectStarted)
                    connectStarted = -1L
                }
                override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = finishConnect()
                override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
                    finishTls()
                    finishConnect()
                    sample.put("connectionFailures", sample.optInt("connectionFailures") + 1)
                }
                override fun secureConnectStart(call: Call) { tlsStarted = SystemClock.elapsedRealtime() }
                private fun finishTls() {
                    if (tlsStarted >= 0) sample.put("tlsMs", sample.optLong("tlsMs") + SystemClock.elapsedRealtime() - tlsStarted)
                    tlsStarted = -1L
                }
                override fun secureConnectEnd(call: Call, handshake: Handshake?) = finishTls()
                override fun callFailed(call: Call, ioe: IOException) {
                    if (dnsStarted >= 0) {
                        sample.put("dnsFailures", sample.optInt("dnsFailures") + 1)
                        finishDns()
                    }
                }
                override fun responseHeadersEnd(call: Call, response: Response) {
                    sample.put("protocol", response.protocol.toString())
                        .put("headerMs", SystemClock.elapsedRealtime() - started)
                }
            })
        }.build()
        val source = StorageReadHttpDataSource.Factory(OkHttpDataSource.Factory(client)).createDataSource()
        var bytes = 0
        var firstByte = -1L
        val prefixDigest = MessageDigest.getInstance("SHA-256")
        try {
            source.open(DataSpec.Builder().setUri(url).setLength(if (range) PREFIX_BYTES.toLong() else C.LENGTH_UNSET.toLong()).build())
            sample.put("status", source.responseCode).put("sourceR2", source.responseHeaders.any {
                it.key.equals("x-novvin-media-source", true) && "r2" in it.value
            })
            val scratch = ByteArray(8192)
            while (bytes < PREFIX_BYTES) {
                val count = source.read(scratch, 0, minOf(scratch.size, PREFIX_BYTES - bytes))
                if (count < 0) break
                check(count > 0)
                if (firstByte < 0) firstByte = SystemClock.elapsedRealtime() - started
                prefixDigest.update(scratch, 0, count)
                bytes += count
            }
        } catch (failure: Exception) {
            sample.put("errorType", failure.javaClass.simpleName)
        } finally {
            runCatching { source.close() }
        }
        return sample.put("bytes", bytes).put("firstByteMs", firstByte)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("prefixSha256", prefixDigest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun digest(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(android.net.Uri.parse(url).encodedPath.orEmpty().toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object { const val PREFIX_BYTES = 65_536 }
}
