package conectate.com.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import conectate.com.data.model.Hashtag as HashtagModel

@Entity(tableName = "hashtags")
data class HashtagEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "post_count") val postCount: Int = 0,
    @ColumnInfo(name = "video_count") val videoCount: Int = 0,
    @ColumnInfo(name = "trending_score") val trendingScore: Double = 0.0,
    @ColumnInfo(name = "created_at") val createdAt: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: String? = null
) {
    fun toDomain(): HashtagModel = HashtagModel(
        id = id,
        name = name,
        postCount = postCount,
        videoCount = videoCount,
        trendingScore = trendingScore,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(hashtag: HashtagModel): HashtagEntity = HashtagEntity(
            id = hashtag.id,
            name = hashtag.name.lowercase(),
            postCount = hashtag.postCount,
            videoCount = hashtag.videoCount,
            trendingScore = hashtag.trendingScore,
            createdAt = hashtag.createdAt,
            updatedAt = hashtag.updatedAt
        )
    }
}
