package conectate.com.data.model
import org.junit.Assert.*
import org.junit.Test
class SharedVideoReferenceTest {
    private val id="18ac0a9c-079e-45ba-87bf-bbbe64073777"
    private val url="https://red-social-android.vercel.app/app/reels/$id"
    @Test fun currentLinksBecomeCards() { assertEquals(id, sharedVideoReference(url)?.videoId) }
    @Test fun olderTextWallBecomesCardAndCaption() { val r=sharedVideoReference("¡Mira este video!\n$url\n\nPrueba"); assertEquals(id,r?.videoId); assertEquals("Prueba",r?.caption) }
    @Test fun directAppLinksBecomeCards() { assertEquals(id,sharedVideoReference("conectate://video/$id")?.videoId) }
    @Test fun arbitraryHostsCannotBecomeCards() { assertNull(sharedVideoReference("https://attacker.invalid/app/reels/$id")); assertNull(sharedVideoReference("https://red-social-android.vercel.app.attacker.invalid/app/reels/$id")) }
    @Test fun ordinaryTextAndMultipleLinksStayOrdinary() { assertNull(sharedVideoReference("Hola")); assertNull(sharedVideoReference("$url $url")) }
    @Test fun captionsAreBounded() { assertEquals(800,sharedVideoReference(url+"\n"+"x".repeat(900))?.caption?.length) }
}
