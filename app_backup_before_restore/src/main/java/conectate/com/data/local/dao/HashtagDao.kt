package conectate.com.data.local.dao

import androidx.room.*
import conectate.com.data.local.entity.HashtagEntity
import conectate.com.data.local.entity.PostHashtagEntity
import conectate.com.data.local.entity.VideoHashtagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HashtagDao {

    // --- HASHTAGS ---
    @Query("SELECT * FROM hashtags WHERE name = :name LIMIT 1")
    suspend fun getHashtagByName(name: String): HashtagEntity?

    @Query("SELECT * FROM hashtags WHERE id = :id")
    suspend fun getHashtagById(id: String): HashtagEntity?

    @Query("SELECT * FROM hashtags ORDER BY trending_score DESC, updated_at DESC LIMIT :limit")
    suspend fun getTrendingHashtags(limit: Int = 20): List<HashtagEntity>

    @Query("SELECT * FROM hashtags WHERE name LIKE '%' || :query || '%' ORDER BY post_count + video_count DESC LIMIT :limit")
    suspend fun searchHashtags(query: String, limit: Int = 10): List<HashtagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHashtag(hashtag: HashtagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPostHashtag(postHashtag: PostHashtagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVideoHashtag(videoHashtag: VideoHashtagEntity)

    @Query("UPDATE hashtags SET post_count = post_count + 1 WHERE id = :hashtagId")
    suspend fun incrementPostCount(hashtagId: String)

    @Query("UPDATE hashtags SET video_count = video_count + 1 WHERE id = :hashtagId")
    suspend fun incrementVideoCount(hashtagId: String)

    @Query("UPDATE hashtags SET post_count = post_count - 1 WHERE id = :hashtagId AND post_count > 0")
    suspend fun decrementPostCount(hashtagId: String)

    @Query("UPDATE hashtags SET video_count = video_count - 1 WHERE id = :hashtagId AND video_count > 0")
    suspend fun decrementVideoCount(hashtagId: String)

    @Delete
    suspend fun deleteHashtag(hashtag: HashtagEntity)

    // --- POST HASHTAGS ---
    @Query("SELECT * FROM post_hashtags WHERE post_id = :postId")
    suspend fun getPostHashtags(postId: String): List<PostHashtagEntity>

    @Delete
    suspend fun deletePostHashtag(postHashtag: PostHashtagEntity)

    @Query("DELETE FROM post_hashtags WHERE post_id = :postId")
    suspend fun deletePostHashtagsForPost(postId: String)

    // --- VIDEO HASHTAGS ---
    @Query("SELECT * FROM video_hashtags WHERE video_id = :videoId")
    suspend fun getVideoHashtags(videoId: String): List<VideoHashtagEntity>

    @Delete
    suspend fun deleteVideoHashtag(videoHashtag: VideoHashtagEntity)

    @Query("DELETE FROM video_hashtags WHERE video_id = :videoId")
    suspend fun deleteVideoHashtagsForVideo(videoId: String)

    // --- OBSERVABLES ---
    @Query("SELECT * FROM hashtags ORDER BY trending_score DESC LIMIT :limit")
    fun observeTrendingHashtags(limit: Int = 20): Flow<List<HashtagEntity>>

    @Query("SELECT * FROM hashtags WHERE name LIKE '%' || :query || '%' ORDER BY post_count + video_count DESC LIMIT :limit")
    fun observeSearchHashtags(query: String, limit: Int = 10): Flow<List<HashtagEntity>>
}