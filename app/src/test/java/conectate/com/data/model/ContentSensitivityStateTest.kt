package conectate.com.data.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ContentSensitivityStateTest {
    @Test fun reactingToAPostKeepsItsSensitiveContentLabel() {
        val post = Post(id = "p", userId = "u", userName = "Autor").also { it.isSensitive = true }
        assertTrue(post.copy(likedByMe = true, likeCount = 1).isSensitive)
    }

    @Test fun sharingAVideoKeepsItsSensitiveContentLabel() {
        val video = Video(id = "v").also { it.isSensitive = true }
        assertTrue(video.copy(sharedByMe = true, shareCount = 1).isSensitive)
    }

    @Test fun aPostClassificationChangeIsPublishedToTheScreen() {
        val original = Post(id = "p", userId = "u", userName = "Autor")
        val state = MutableStateFlow(original)
        state.value = original.copy().also { it.isSensitive = true }
        assertTrue(state.value.isSensitive)
    }

    @Test fun aVideoClassificationChangeIsPublishedToTheScreen() {
        val original = Video(id = "v")
        val state = MutableStateFlow(original)
        state.value = original.copy().also { it.isSensitive = true }
        assertTrue(state.value.isSensitive)
    }

    @Test fun olderCachedContentWithoutTheFlagStillLoads() {
        assertFalse(Json.decodeFromString<Post>("""{"id":"p","user_id":"u","user_name":"Autor"}""").isSensitive)
        assertFalse(Json.decodeFromString<Video>("""{"id":"v"}""").isSensitive)
    }

    @Test fun serverClassificationsSurviveDeserializationAndSubsequentEdits() {
        val post = Json.decodeFromString<Post>("""{"id":"p","user_id":"u","user_name":"Autor","is_sensitive":true}""")
        val video = Json.decodeFromString<Video>("""{"id":"v","is_sensitive":true}""")
        assertTrue(post.copy(commentCount = 2).isSensitive)
        assertTrue(video.copy(commentCount = 2).isSensitive)
    }
}
