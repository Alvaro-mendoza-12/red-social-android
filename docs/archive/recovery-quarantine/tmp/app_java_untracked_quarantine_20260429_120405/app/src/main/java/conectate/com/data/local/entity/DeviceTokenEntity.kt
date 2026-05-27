package conectate.com.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_tokens")
data class DeviceTokenEntity(
    @PrimaryKey val id: String, // userId
    val token: String,
    val updatedAt: Long = System.currentTimeMillis()
)