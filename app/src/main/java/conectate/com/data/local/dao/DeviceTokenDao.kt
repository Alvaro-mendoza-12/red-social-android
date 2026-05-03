package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import conectate.com.data.local.entity.DeviceTokenEntity

@Dao
interface DeviceTokenDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DeviceTokenEntity): Long

    @Query("SELECT * FROM device_tokens WHERE id = :userId")
    suspend fun getTokenByUserId(userId: String): DeviceTokenEntity?

    @Delete
    suspend fun delete(entity: DeviceTokenEntity): Int

    @Query("DELETE FROM device_tokens WHERE id = :userId")
    suspend fun deleteByUserId(userId: String): Int
}