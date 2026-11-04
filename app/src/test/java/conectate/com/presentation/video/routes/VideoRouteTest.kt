package conectate.com.presentation.video.routes

import conectate.com.data.model.Video
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class VideoRouteTest {
    private fun video(id: String, author: String = "a") = Video(
        id = id, userId = author, userName = "Autor $author", title = "Video $id",
        videoUrl = "https://media.example/$id.mp4?secret=never-save-this",
    )

    @Test fun selectionIsFiniteAndAlternatesAuthorsWithoutDuplicatingIds() {
        val route = prepareVideoRoute(" guitarra ", 5, listOf(
            video("a1"), video("A1"), video("a2"), video("a3"), video("b1", "b"),
            video("b2", "b"), video("c1", "c"),
        ))!!
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2"), route.items.map { it.videoId })
        assertEquals(3, route.authorCount)
        assertEquals("guitarra", route.topic)
    }

    @Test fun shortCatalogReportsItsActualCountAndUnavailableVideosAreExcluded() {
        val route = prepareVideoRoute("tema", 5, listOf(
            video("public"), video("private").copy(isPublic = false),
            video("sensitive").copy(isSensitive = true), video("missing").copy(videoUrl = ""),
            video("no-author").copy(userId = ""), video(""),
        ))!!
        assertEquals(1, route.items.size)
        assertEquals(5, route.requestedCount)
        assertNull(prepareVideoRoute("vacío", 3, emptyList()))
    }

    @Test fun selectionNeverScansBeyondTheExistingFirstPageBudget() {
        val candidates = (1..81).map { video("$it").copy(isPublic = it == 81) }
        assertNull(prepareVideoRoute("tema", 3, candidates))
    }

    @Test fun serializedRecordContainsReferencesAndExplicitChoicesWithoutPlaybackSecrets() {
        val route = prepareVideoRoute("guitarra", 3, listOf(video("one")))!!
            .open("ONE").toggleUseful("one").copy(conclusion = "Practicar acordes")
        val encoded = Json.encodeToString(route)
        assertFalse(encoded.contains("https://"))
        assertFalse(encoded.contains("secret"))
        val restored = Json.decodeFromString<VideoRoute>(encoded).validate()
        assertEquals(route, restored)
        assertEquals(1, restored.openedCount)
        assertEquals(1, restored.usefulCount)
    }

    @Test fun usefulnessRequiresAnOpeningAndCanBeUndoneWithoutChangingOtherItems() {
        val route = prepareVideoRoute("tema", 3, listOf(video("one"), video("two")))!!
        assertEquals(route, route.toggleUseful("one"))
        val useful = route.open("one").toggleUseful("ONE")
        assertTrue(useful.items[0].useful)
        assertFalse(useful.items[1].opened)
        assertEquals(0, useful.toggleUseful("one").usefulCount)
        assertEquals(useful, useful.open("unknown"))
    }

    @Test fun unsupportedSizesAndEmptyTopicsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { prepareVideoRoute("  ", 3, listOf(video("one"))) }
        assertThrows(IllegalArgumentException::class.java) { prepareVideoRoute("tema", 100, listOf(video("one"))) }
        assertThrows(IllegalArgumentException::class.java) { prepareVideoRoute("x".repeat(81), 3, listOf(video("one"))) }
    }

    @Test fun routePlaybackHasExactlyItsSelectedVideoAndNeverSubstitutesAnotherOne() {
        val selected = video("Video-A")
        val catalog = listOf(video("other"), selected, video("VIDEO-A"), video("next"))
        assertEquals(listOf(selected), routePlaybackVideos(catalog, " video-a "))
        assertTrue(routePlaybackVideos(catalog, "removed").isEmpty())
        assertTrue(routePlaybackVideos(catalog, null).isEmpty())
        assertTrue(routePlaybackVideos(catalog, " ").isEmpty())
    }

    @Test fun savedRecordsRejectUnknownSchemasDuplicateIdsAndOversizedConclusions() {
        val route = prepareVideoRoute("tema", 3, listOf(video("one")))!!
        assertThrows(IllegalArgumentException::class.java) { route.copy(schemaVersion = 2).validate() }
        assertThrows(IllegalArgumentException::class.java) { route.copy(items = listOf(route.items[0], route.items[0].copy(videoId = "ONE"))).validate() }
        assertThrows(IllegalArgumentException::class.java) { route.copy(conclusion = "x".repeat(1001)).validate() }
        assertThrows(IllegalArgumentException::class.java) { route.copy(items = emptyList()).validate() }
    }
}
