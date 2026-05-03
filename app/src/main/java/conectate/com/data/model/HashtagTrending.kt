package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class HashtagTrending(
    val id: String,
    val name: String,
    val videoCount: Int,
    val viewCount: Int,
    val rank: Int,
    val trendDirection: String = "stable", // "up", "down", "stable"
    val trendPercentage: Float = 0f
) {
    companion object {
        fun getMockTrending(): List<HashtagTrending> = listOf(
            HashtagTrending("1", "fyp", 1500000, 50000000, 1, "up", 12.5f),
            HashtagTrending("2", "viral", 980000, 32000000, 2, "up", 8.3f),
            HashtagTrending("3", "trending", 750000, 21000000, 3, "stable", 0f),
            HashtagTrending("4", "comedy", 650000, 18000000, 4, "down", -2.1f),
            HashtagTrending("5", "dance", 580000, 15000000, 5, "up", 15.7f),
            HashtagTrending("6", "music", 520000, 14000000, 6, "stable", 0f),
            HashtagTrending("7", "fashion", 420000, 11000000, 7, "up", 6.2f),
            HashtagTrending("8", "food", 380000, 9500000, 8, "down", -4.5f),
            HashtagTrending("9", "travel", 320000, 8200000, 9, "up", 9.1f),
            HashtagTrending("10", "fitness", 290000, 7500000, 10, "stable", 0f)
        )
    }
}
