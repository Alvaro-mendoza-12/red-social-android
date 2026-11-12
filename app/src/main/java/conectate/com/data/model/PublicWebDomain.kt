package conectate.com.data.model

/** Public links use the branded HTTPS origin; installed legacy links remain accepted. */
object PublicWebDomain {
    const val activeOrigin = "https://novvinapp.com"
    const val registeredOrigin = "https://novvinapp.com"
    val httpsHosts = setOf("red-social-android.vercel.app", "novvinapp.com", "www.novvinapp.com")
}
