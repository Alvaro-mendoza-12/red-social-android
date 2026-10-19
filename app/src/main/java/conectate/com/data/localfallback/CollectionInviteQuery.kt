package conectate.com.data.localfallback

import java.net.URLEncoder

/** A private community invite needs the same preview token as a group invite. */
internal fun collectionInviteQuery(collection: String, rawToken: String?): String {
    val token = rawToken?.trim().orEmpty()
    require(collection != "groups" || token.isNotBlank()) { "La invitación del grupo está incompleta." }
    return if (token.isBlank()) "" else "?token=${URLEncoder.encode(token, "UTF-8")}"
}
