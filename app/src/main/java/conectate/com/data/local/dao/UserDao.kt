package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import conectate.com.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<UserEntity>)

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUser(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE id IN (:userIds)")
    suspend fun getUsers(userIds: List<String>): List<UserEntity>

    @Query("SELECT * FROM users WHERE id = :userId")
    fun getUserSync(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE id IN (:userIds)")
    fun getUsersFlow(userIds: List<String>): Flow<List<UserEntity>>
}
