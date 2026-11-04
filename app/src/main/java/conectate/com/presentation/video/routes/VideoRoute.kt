package conectate.com.presentation.video.routes

import conectate.com.data.model.Video
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable

/** Device-only references. Playback URLs and credentials must never be saved in this record. */
@Serializable
data class VideoRouteItem(
    val videoId: String,
    val title: String,
    val authorId: String,
    val authorName: String,
    val opened: Boolean = false,
    val useful: Boolean = false,
)

@Serializable
data class VideoRoute(
    val id: String,
    val topic: String,
    val requestedCount: Int,
    val items: List<VideoRouteItem>,
    val revision: Long = 0,
    val conclusion: String = "",
    val completed: Boolean = false,
    val schemaVersion: Int = 1,
) {
    val openedCount: Int get() = items.count(VideoRouteItem::opened)
    val usefulCount: Int get() = items.count(VideoRouteItem::useful)
    val authorCount: Int get() = items.map { routeIdentity(it.authorId) }.distinct().size

    fun validate(): VideoRoute {
        require(schemaVersion == 1 && revision >= 0) { "Esta ruta necesita una versión compatible de la aplicación." }
        require(id.isNotBlank() && id.length <= 128)
        require(topic.isNotBlank() && topic.length <= MAX_TOPIC)
        require(requestedCount in SIZES && items.size in 1..requestedCount)
        require(items.map { routeIdentity(it.videoId) }.distinct().size == items.size)
        require(items.all {
            it.videoId.isNotBlank() && it.videoId.length <= 128 &&
                it.authorId.isNotBlank() && it.authorId.length <= 128 &&
                it.title.length <= 200 && it.authorName.length <= 80 && (!it.useful || it.opened)
        })
        require(conclusion.length <= MAX_CONCLUSION)
        return this
    }

    fun open(videoId: String): VideoRoute = copy(items = items.map {
        if (it.videoId.equals(videoId, ignoreCase = true)) it.copy(opened = true) else it
    })

    fun toggleUseful(videoId: String): VideoRoute = copy(items = items.map {
        if (it.opened && it.videoId.equals(videoId, ignoreCase = true)) it.copy(useful = !it.useful) else it
    })

    companion object {
        val SIZES = listOf(3, 5)
        const val MAX_TOPIC = 80
        const val MAX_CONCLUSION = 1000
    }
}

/** Stable rounds of authors preserve result order within each author, using at most 80 candidates. */
internal fun prepareVideoRoute(topic: String, count: Int, videos: List<Video>): VideoRoute? {
    val query = topic.trim()
    require(query.isNotBlank() && query.length <= VideoRoute.MAX_TOPIC)
    require(count in VideoRoute.SIZES)
    val candidates = videos.take(80).filter {
        it.isPublic && !it.isSensitive && it.id.isNotBlank() && it.id.length <= 128 &&
            it.userId.isNotBlank() && it.userId.length <= 128 && it.videoUrl.isNotBlank()
    }.distinctBy { routeIdentity(it.id) }
    val rounds = candidates.groupBy { routeIdentity(it.userId) }.values.flatMap { authorVideos ->
        authorVideos.mapIndexed { round, video -> round to video }
    }
    val items = rounds.sortedBy { it.first }.take(count).map { (_, video) ->
        VideoRouteItem(
            videoId = video.id.trim(),
            title = video.title.trim().ifBlank { "Video de ${video.userName.ifBlank { "la comunidad" }}" }.take(200),
            authorId = video.userId.trim(),
            authorName = video.userName.trim().ifBlank { "Creador de Novvin" }.take(80),
        )
    }
    if (items.isEmpty()) return null
    return VideoRoute(UUID.randomUUID().toString(), query, count, items).validate()
}

internal fun routeIdentity(value: String): String = value.trim().lowercase(Locale.ROOT)

/** The route player keeps only its selected reference, even if unrelated metadata arrives. */
internal fun routePlaybackVideos(videos: List<Video>, videoId: String?): List<Video> {
    val id = videoId?.let(::routeIdentity)?.takeIf(String::isNotBlank) ?: return emptyList()
    return listOfNotNull(videos.firstOrNull { routeIdentity(it.id) == id })
}
