package conectate.com.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_settings")
data class ChatSettingsEntity(
    @PrimaryKey
    @ColumnInfo(name = "chat_id")
    val chatId: String,
    @ColumnInfo(name = "background_type", defaultValue = "'default'")
    val backgroundType: String = "default",
    @ColumnInfo(name = "background_uri")
    val backgroundUri: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String = java.time.Instant.now().toString()
)

