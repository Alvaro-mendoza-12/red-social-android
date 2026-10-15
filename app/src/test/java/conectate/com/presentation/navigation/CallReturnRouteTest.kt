package conectate.com.presentation.navigation

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, manifest = Config.NONE)
class CallReturnRouteTest {
    private val id = "5915f62a-7778-49dc-aeed-1fb3f56b8aa1"
    @Test fun acceptedCallHasAnExplicitDestination() { assertEquals("call/$id", resolveCallReturnRoute(id)) }
    @Test fun emptyAndMalformedExtrasCannotCreateRoutes() {
        for(value in listOf(null,""," ","../login", "test?account=other", "$id/extra")) assertNull(resolveCallReturnRoute(value))
    }
    @Test fun normalizedIdentifierIsSafe() { assertEquals("call/$id",resolveCallReturnRoute(" $id ")) }
}
