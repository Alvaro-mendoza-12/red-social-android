package conectate.com.presentation.video

/** A response belongs to one opening of a comment sheet, including reopening the same video. */
internal class VideoCommentSession(private val account: () -> String?) {
    class Ticket(val videoId: String, val accountId: String?)
    private var active: Ticket? = null
    fun open(videoId: String) { active = Ticket(videoId, account()) }
    fun close() { active = null }
    fun capture(videoId: String): Ticket? = active?.takeIf { it.videoId == videoId && it.accountId == account() }
    fun isCurrent(ticket: Ticket): Boolean = active === ticket && ticket.accountId == account()
}
