package conectate.com.presentation.navigation

import org.junit.Assert.*
import org.junit.Test

class LiveShareLinksTest {
    private val id = "04253203-9341-4557-9ece-c54da1be335a"
    @Test fun sharesTheRegisteredDomainAndAlwaysOpensAsViewer() {
        assertEquals("https://novvinapp.com/app/live/$id", liveShareLink(id))
        for (host in listOf("novvinapp.com", "www.novvinapp.com", "red-social-android.vercel.app")) {
            assertEquals(resolveLiveStartedRoute(id), liveRouteFromShareLink("https://$host/app/live/$id?host=1"))
        }
    }
    @Test fun rejectsUntrustedOriginsAndInjectedRoutes() {
        for (url in listOf("http://novvinapp.com/app/live/$id", "https://novvinapp.com.evil.test/app/live/$id",
            "https://user@novvinapp.com/app/live/$id", "https://novvinapp.com:444/app/live/$id",
            "https://novvinapp.com/app/live/$id/extra", "https://novvinapp.com/app/live/$id#host=1",
            "https://novvinapp.com/app/live/------------------------------------")) assertNull(url, liveRouteFromShareLink(url))
    }
}
