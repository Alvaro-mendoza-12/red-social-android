package conectate.com.data.localfallback

import androidx.test.core.app.ApplicationProvider
import conectate.com.data.model.Video
import conectate.com.data.model.VideoComment
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.test.UnitTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class PublicAuthorRoleMappingTest {
    private lateinit var config: SupabaseConfig
    private lateinit var api: LocalFallbackApiClient
    @Before fun prepare() {
        config = SupabaseConfig(ApplicationProvider.getApplicationContext())
        api = LocalFallbackApiClient(config)
    }
    @After fun closeClient() { config.httpClient.close() }
    private fun row(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun aReservedLookingNameWithoutAnAuthorizedRoleStaysAnOrdinaryAuthor() {
        val user = api.mapProfileUser(row("""{"id":"user","username":"novvin","display_name":"Alvaro"}"""))
        val video = api.mapVideo(row("""{"id":"video","user_name":"Novvin"}"""))
        val comment = api.mapVideoComment(row("""{"id":"comment","user_name":"Alvaro"}"""), "video")
        assertFalse(user.is_admin)
        assertFalse(video.isAdmin)
        assertFalse(comment.isAdmin)
    }

    @Test fun authorizedRolesAndVerificationReachTheRealNativeMappers() {
        val payload = row("""{"id":"item","user_id":"author","user_name":"María","is_admin":true,"is_verified":true}""")
        assertTrue(api.mapProfileUser(payload).is_admin)
        val video = api.mapVideo(payload)
        assertTrue(video.isAdmin)
        assertTrue(video.isVerified)
        val comment = api.mapVideoComment(payload, "video")
        assertTrue(comment.isAdmin)
        assertTrue(comment.isVerified)
    }

    @Test fun explicitRevocationIsNotOverriddenByTheAuthorsName() {
        val payload = row("""{"id":"item","user_name":"Novvin","is_admin":false}""")
        assertFalse(api.mapVideo(payload).isAdmin)
        assertFalse(api.mapVideoComment(payload, "video").isAdmin)
    }

    @Test fun roleInformationSurvivesReactionsAndEdits() {
        val payload = row("""{"id":"item","is_admin":true,"is_verified":true}""")
        assertTrue(api.mapVideo(payload).copy(likedByMe = true).isAdmin)
        assertTrue(api.mapVideoComment(payload, "video").copy(content = "Editado").isAdmin)
    }

    @Test fun existingCachedVideosAndCommentsWithoutRolesStillLoad() {
        assertFalse(Json.decodeFromString<Video>("""{"id":"video"}""").isAdmin)
        assertFalse(Json.decodeFromString<VideoComment>("""{"id":"comment"}""").isAdmin)
    }
}
