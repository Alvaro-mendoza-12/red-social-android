package conectate.com.data.repository

import conectate.com.data.local.dao.FollowDao
import conectate.com.data.local.entity.FollowEntity
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FollowRepository @Inject constructor(
    private val followDao: FollowDao
) {

    suspend fun followUser(followerId: String, followedId: String): Boolean {
        if (followerId.isBlank() || followedId.isBlank() || followerId == followedId) return false
        return followDao.follow(FollowEntity(followerId = followerId, followedId = followedId)) != -1L
    }

    suspend fun unfollowUser(followerId: String, followedId: String): Boolean {
        if (followerId.isBlank() || followedId.isBlank()) return false
        return followDao.unfollow(FollowEntity(followerId = followerId, followedId = followedId)) > 0
    }

    suspend fun isFollowing(followerId: String, followedId: String): Boolean {
        if (followerId.isBlank() || followedId.isBlank()) return false
        return followDao.isFollowing(followerId, followedId)
    }

    fun getFollowersFlow(userId: String) = followDao.getFollowers(userId).map { rows ->
        rows.map { it.toDomain() }
    }

    fun getFollowingFlow(userId: String) = followDao.getFollowing(userId).map { rows ->
        rows.map { it.toDomain() }
    }

    suspend fun getFollowerCount(userId: String): Int = followDao.getFollowerCount(userId)

    suspend fun getFollowingCount(userId: String): Int = followDao.getFollowingCount(userId)
}
