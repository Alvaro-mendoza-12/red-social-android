package conectate.com.presentation.social

/**
 * Keeps a bad or stale recommendation from taking down the Social surface.
 *
 * Recommendation cards come from data that can be refreshed independently of
 * navigation. The id is therefore validated before constructing a route and
 * navigation failures are represented as a result for the caller to handle.
 */
internal sealed class RecommendedVideoNavigationResult {
    data object Navigated : RecommendedVideoNavigationResult()
    data object InvalidVideoId : RecommendedVideoNavigationResult()
    data class Failed(val cause: Throwable) : RecommendedVideoNavigationResult()
}

internal fun attemptRecommendedVideoNavigation(
    videoId: String?,
    routeFor: (String) -> String,
    navigate: (String) -> Unit,
): RecommendedVideoNavigationResult {
    val cleanId = videoId?.trim().orEmpty()
    if (cleanId.isEmpty() || cleanId.length > MAX_RECOMMENDED_VIDEO_ID_LENGTH) {
        return RecommendedVideoNavigationResult.InvalidVideoId
    }

    return runCatching {
        navigate(routeFor(cleanId))
        RecommendedVideoNavigationResult.Navigated
    }.getOrElse(RecommendedVideoNavigationResult::Failed)
}

private const val MAX_RECOMMENDED_VIDEO_ID_LENGTH = 512
