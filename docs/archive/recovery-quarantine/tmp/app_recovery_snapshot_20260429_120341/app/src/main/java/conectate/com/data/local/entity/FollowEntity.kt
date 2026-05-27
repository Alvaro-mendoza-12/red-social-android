package conectate.com.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "follows",
    primaryKeys = ["followerId", "followedId"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["followerId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["followedId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["followerId"]),
        Index(value = ["followedId"])
    ]
)
data class FollowEntity(
    @ColumnInfo(name = "followerId") val followerId: String,
    @ColumnInfo(name = "followedId") val followedId: String,
    @ColumnInfo(name = "createdAt") val createdAt: Long = System.currentTimeMillis()
)