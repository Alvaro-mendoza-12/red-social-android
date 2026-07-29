package conectate.com.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.last

data class OfflineVideoPrefetchResult(
    val requested: Int,
    val available: Int,
    val downloaded: Int,
    val failed: Int
)

@Singleton
class OfflineVideoPrefetchCoordinator @Inject constructor(
    private val videoRepository: VideoRepository,
    private val offlineVideoRepository: OfflineVideoRepository
) {
    suspend fun replenish(): OfflineVideoPrefetchResult {
        val target = offlineVideoRepository.configuredTargetCount().coerceIn(0, MAX_TARGET)
        if (target == 0) {
            return OfflineVideoPrefetchResult(0, offlineVideoRepository.videos.value.size, 0, 0)
        }

        if (offlineVideoRepository.consumeFreshCycleRequest()) {
            offlineVideoRepository.removeAll().getOrThrow()
        } else {
            offlineVideoRepository.refreshForCurrentAccount()
        }

        val existingIds = offlineVideoRepository.videos.value
            .map { it.video.id.trim().lowercase() }
            .toMutableSet()
        var downloaded = 0
        var failed = 0
        var offset = 0
        var emptyPages = 0

        while (existingIds.size < target && offset < MAX_FETCH_OFFSET && emptyPages < 2) {
            val page = videoRepository.getVideos(
                offset = offset,
                limit = PAGE_SIZE,
                random = true
            ).last()
            val candidates = page
                .asSequence()
                .filter { it.id.isNotBlank() && it.videoUrl.isNotBlank() }
                .filterNot { existingIds.contains(it.id.trim().lowercase()) }
                .distinctBy { it.id.trim().lowercase() }
                .toList()
                .shuffled()

            if (candidates.isEmpty()) {
                emptyPages += 1
            } else {
                emptyPages = 0
                for (video in candidates) {
                    if (existingIds.size >= target) break
                    offlineVideoRepository.download(video)
                        .onSuccess {
                            existingIds += video.id.trim().lowercase()
                            downloaded += 1
                        }
                        .onFailure {
                            failed += 1
                        }
                }
            }
            offset += PAGE_SIZE
        }

        offlineVideoRepository.refreshForCurrentAccount()
        return OfflineVideoPrefetchResult(
            requested = target,
            available = offlineVideoRepository.videos.value.size,
            downloaded = downloaded,
            failed = failed
        )
    }

    private companion object {
        private const val PAGE_SIZE = 60
        private const val MAX_FETCH_OFFSET = 600
        private const val MAX_TARGET = 150
    }
}
