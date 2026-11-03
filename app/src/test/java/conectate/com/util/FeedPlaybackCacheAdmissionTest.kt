package conectate.com.util

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import conectate.com.core.media.StorageReadCredential
import conectate.com.core.media.StorageReadIdentity
import conectate.com.core.media.StorageReadSession
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.test.UnitTestApplication
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FeedPlaybackCacheAdmissionTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var cache: SimpleCache
    private lateinit var database: StandaloneDatabaseProvider
    private val url get() = UbuntuStorageUrl.baseUrl + "/storage/v1/object/public/videos/owner/feed.mp4"
    private val originalKey get() = requireNotNull(UbuntuStorageUrl.stableCacheKey(url))
    private val requests = mutableListOf<Int>()
    private var revision = "original"
    private var denied = false
    private val stored = ByteArray(16) { 12 }
    private val remote = ByteArray(16) { 42 }

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext<Context>())
        cache = SimpleCache(folder.newFolder(), NoOpCacheEvictor(), database)
        StorageReadSession.install { credential("member", 1) }
    }

    @After fun tearDown() {
        StorageReadSession.install { null }
        cache.release()
        database.close()
    }

    private fun credential(account: String, generation: Long) =
        StorageReadCredential(StorageReadIdentity(account, generation), "fixture.jwt")

    private fun spec(position: Long) = DataSpec.Builder().setUri(url).setPosition(position)
        .setLength(4).setKey(originalKey).build()

    private val upstream = DataSource.Factory {
        val data = ByteArrayDataSource(remote)
        object : DataSource by data {
            override fun open(dataSpec: DataSpec): Long {
                requests += dataSpec.httpMethod
                if (denied) throw IOException("Access revoked")
                return if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_HEAD) 0L else data.open(dataSpec)
            }

            override fun getResponseHeaders(): Map<String, List<String>> = mapOf(
                "X-Novvin-Playback-Cache" to listOf("permission-check-v1"),
                "ETag" to listOf(revision),
                "Content-Type" to listOf("video/mp4"),
            )
        }
    }

    private fun cached(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache).setUpstreamDataSourceFactory(upstream)

    private fun playback(preferDirect: Boolean = true): DataSource {
        val existingCache = if (preferDirect) ({ cache }) else null
        return StorageReadDataSource(upstream, cached(upstream)) { spec ->
            PlaybackCacheAdmission.key(spec, sourceFactory = upstream, existingCache = existingCache)
        }
    }

    private fun seed(position: Long = 0L) {
        val key = requireNotNull(PlaybackCacheAdmission.revisionKey(
            url, originalKey, "permission-check-v1", revision, "video/mp4", false,
        ))
        val source = cached { ByteArrayDataSource(stored) }.createDataSource()
        read(source, spec(position).buildUpon().setKey(key).build())
    }

    private fun read(source: DataSource, spec: DataSpec): ByteArray {
        try {
            source.open(spec)
            val bytes = ByteArray(4)
            var offset = 0
            while (offset < bytes.size) {
                val count = source.read(bytes, offset, bytes.size - offset)
                check(count > 0)
                offset += count
            }
            return bytes
        } finally {
            source.close()
        }
    }

    @Test fun emptyCacheStartsWithGetWhileOrdinaryAdmissionOpensHeadThenGet() {
        assertArrayEquals(remote.copyOf(4), read(playback(), spec(0)))
        assertEquals(listOf(DataSpec.HTTP_METHOD_GET), requests)
        requests.clear()
        assertArrayEquals(remote.copyOf(4), read(playback(preferDirect = false), spec(0)))
        assertEquals(listOf(DataSpec.HTTP_METHOD_HEAD, DataSpec.HTTP_METHOD_GET), requests)
    }

    @Test fun warmBytesStillRequireFreshPermissionAndRevisionOnEveryOpen() {
        seed()
        repeat(2) { assertArrayEquals(stored.copyOf(4), read(playback(), spec(0))) }
        assertEquals(List(2) { DataSpec.HTTP_METHOD_HEAD }, requests)
        denied = true
        assertThrows(IOException::class.java) { read(playback(), spec(0)) }
        assertEquals(List(3) { DataSpec.HTTP_METHOD_HEAD }, requests)
    }

    @Test fun newerRevisionDownloadsFreshBytesInsteadOfTheWarmOldRevision() {
        seed()
        revision = "replacement"
        assertArrayEquals(remote.copyOf(4), read(playback(), spec(0)))
        assertEquals(listOf(DataSpec.HTTP_METHOD_HEAD, DataSpec.HTTP_METHOD_GET), requests)
    }

    @Test fun aHoleOrAnEarlierAccountVisitDoesNotTriggerCacheAdmission() {
        seed(position = 4)
        assertArrayEquals(remote.copyOf(4), read(playback(), spec(0)))
        StorageReadSession.install { credential("member", 2) }
        assertArrayEquals(remote.copyOf(4), read(playback(), spec(4)))
        assertEquals(List(2) { DataSpec.HTTP_METHOD_GET }, requests)
    }
}
