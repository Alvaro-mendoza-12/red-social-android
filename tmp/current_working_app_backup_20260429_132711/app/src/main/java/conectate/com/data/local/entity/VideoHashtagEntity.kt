package conectate.com.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "video_hashtags",
    primaryKeys = ["video_id", "hashtag_id"],
    foreignKeys = [
        ForeignKey(
            entity = HashtagEntity::class,
            parentColumns = ["id"],
            childColumns = ["hashtag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("hashtag_id")
    ]
)
data class VideoHashtagEntity(
    @ColumnInfo(name = "video_id") val videoId: String,
    @ColumnInfo(name = "hashtag_id") val hashtagId: String,
    val position: Int = 0
)
