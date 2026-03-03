package conectate.com.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import conectate.com.data.model.Video

@Entity(tableName = "video_stats")
data class VideoStatsEntity(
    @PrimaryKey val videoId: String,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val shareCount: Int = 0,
    val viewCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
