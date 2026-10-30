package conectate.com.data.repository.moment

import org.junit.Assert.*
import org.junit.Test

class MomentAudiencePolicyTest {
    private fun visible(visibility: String = "all", type: String = "public",
        audience: List<String> = emptyList(), excluded: List<String> = emptyList(), viewer: String = "viewer") =
        canDisplayMomentAudience("author", viewer, type, visibility, excluded, audience)

    @Test fun `selected moment cannot enter another viewers realtime feed`() {
        assertFalse(visible(visibility = "only_selected", audience = listOf("someone-else")))
        assertFalse(visible(visibility = "only_selected"))
    }
    @Test fun `selected viewer can see the moment`() {
        assertTrue(visible(visibility = "only_selected", audience = listOf("viewer")))
    }
    @Test fun `author retains access to their restricted moments`() {
        assertTrue(visible(visibility = "only_selected", type = "private", viewer = "author", excluded = listOf("author")))
    }
    @Test fun `exclusion wins over selected audience membership`() {
        assertFalse(visible(visibility = "only_selected", audience = listOf("viewer"), excluded = listOf("viewer")))
    }
    @Test fun `private and silent moments stay out of another feed`() {
        assertFalse(visible(type = "private"))
        assertFalse(visible(type = "silent"))
    }
    @Test fun `public audience is unaffected`() {
        assertTrue(visible())
    }
}
