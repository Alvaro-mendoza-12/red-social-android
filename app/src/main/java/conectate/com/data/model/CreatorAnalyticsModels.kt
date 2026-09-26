package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CreatorAnalyticsCatalog(
    val version: Int = 1,
    val items: List<CreatorAnalyticsContent> = emptyList(),
)

@Serializable
data class CreatorAnalyticsContent(
    val contentType: String,
    val contentId: String,
    val title: String = "",
    val description: String = "",
    val thumbnailUrl: String? = null,
    val mediaUrl: String? = null,
    val status: String = "",
    val occurredAt: String? = null,
    val durationSeconds: Double? = null,
    val source: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val viewCount: Long = 0,
    val likeCount: Long = 0,
    val commentCount: Long = 0,
    val shareCount: Long = 0,
) {
    val isVideo: Boolean get() = contentType.equals("video", ignoreCase = true)
    val isLive: Boolean get() = contentType.equals("live", ignoreCase = true)
}

@Serializable
data class CreatorAnalyticsRange(
    val from: String,
    val to: String,
)

@Serializable
data class CreatorAnalyticsPrivacy(
    val minimumCohort: Int = 10,
    val detailedMetricsAvailable: Boolean = false,
    val topDonorsAvailable: Boolean = false,
    val rawViewerIdsReturned: Boolean = false,
    val rawDonorIdsReturned: Boolean = false,
    val smallCountriesSuppressed: Boolean = true,
    val smallTimelineBucketsSuppressed: Boolean = true,
    val smallViewerBucketsSuppressed: Boolean = true,
)

@Serializable
data class VideoAnalyticsReport(
    val version: Int = 1,
    val contentType: String,
    val contentId: String,
    val range: CreatorAnalyticsRange,
    val totals: VideoAnalyticsTotals = VideoAnalyticsTotals(),
    val countries: List<VideoAnalyticsCountry> = emptyList(),
    val timeline: List<VideoAnalyticsTimelinePoint> = emptyList(),
    val privacy: CreatorAnalyticsPrivacy = CreatorAnalyticsPrivacy(),
)

@Serializable
data class VideoAnalyticsTotals(
    val views: Long = 0,
    val uniqueViewers: Long? = null,
    val watchMinutes: Double? = null,
    val averageWatchSeconds: Double? = null,
    val retention: VideoAnalyticsRetention = VideoAnalyticsRetention(),
    val interactions: VideoAnalyticsInteractions = VideoAnalyticsInteractions(),
)

@Serializable
data class VideoAnalyticsRetention(
    val percent25: Double? = null,
    val percent50: Double? = null,
    val percent75: Double? = null,
    val percent100: Double? = null,
)

@Serializable
data class VideoAnalyticsInteractions(
    val likes: Long = 0,
    val comments: Long = 0,
    val shares: Long = 0,
    val saves: Long = 0,
    val follows: Long = 0,
)

@Serializable
data class VideoAnalyticsCountry(
    val countryCode: String,
    val uniqueViewers: Long = 0,
    val views: Long = 0,
)

@Serializable
data class VideoAnalyticsTimelinePoint(
    val bucket: String,
    val views: Long = 0,
    val uniqueViewers: Long = 0,
    val interactions: Long = 0,
)

@Serializable
data class LiveAnalyticsReport(
    val version: Int = 1,
    val contentType: String,
    val contentId: String,
    val range: CreatorAnalyticsRange,
    val totals: LiveAnalyticsTotals = LiveAnalyticsTotals(),
    val topDonors: List<LiveAnalyticsDonor> = emptyList(),
    val viewerCurve: List<LiveAnalyticsViewerPoint> = emptyList(),
    val privacy: CreatorAnalyticsPrivacy = CreatorAnalyticsPrivacy(),
)

@Serializable
data class LiveAnalyticsTotals(
    val peakConcurrent: Long = 0,
    val uniqueViewers: Long? = null,
    val averageConcurrent: Double? = null,
    val viewerMinutes: Double? = null,
    val follows: Long = 0,
    val comments: Long = 0,
    val likes: Long = 0,
    val gifts: Long = 0,
    val giftValueCoins: Long = 0,
)

@Serializable
data class LiveAnalyticsDonor(
    val rank: Int,
    val gifts: Long = 0,
    val valueCoins: Long = 0,
)

@Serializable
data class LiveAnalyticsViewerPoint(
    val bucket: String,
    val concurrent: Long = 0,
)
