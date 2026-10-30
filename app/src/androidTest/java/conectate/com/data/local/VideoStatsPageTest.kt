package conectate.com.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.local.entity.VideoStatsEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class VideoStatsPageTest {
    @Test fun aWholePageUsesOneReadAndKeepsMonotonicViewsWhileAcceptingServerReactions() = runBlocking {
        val statements = CopyOnWriteArrayList<String>()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .setQueryCallback({ sql, _ -> statements += sql }, Executor { it.run() }).build()
        try {
            val dao = db.videoStatsDao()
            dao.insertStats(VideoStatsEntity("VIDEO-0", likeCount = 20, viewCount = 70))
            dao.insertStats(VideoStatsEntity("video-0", likeCount = 15, viewCount = 60))
            statements.clear()
            val result = dao.mergeServerPage((0 until 20).map { VideoStatsEntity("VIDEO-$it", likeCount = 3, commentCount = 2, shareCount = 1, viewCount = 10) })
            val reads = statements.filter { it.trimStart().startsWith("SELECT", true) && it.contains("video_stats", true) }
            assertEquals("Feed page must not issue one read per video", 1, reads.size)
            assertEquals(20, result.size)
            assertEquals(70, result.first().viewCount)
            assertEquals(3, result.first().likeCount)
            assertEquals(2, result.first().commentCount)
            assertEquals(1, result.first().shareCount)
            assertTrue(result.all { it.videoId == it.videoId.lowercase() })
            assertEquals(20, dao.getStatsBatch(result.map { it.videoId }).size)
        } finally { db.close() }
    }

    @Test fun emptyPageDoesNotTouchAnotherVideosStatistics() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val dao = db.videoStatsDao()
            dao.insertStats(VideoStatsEntity("keep", viewCount = 12))
            assertTrue(dao.mergeServerPage(emptyList()).isEmpty())
            assertEquals(12, dao.getStats("keep")!!.viewCount)
        } finally { db.close() }
    }
}
