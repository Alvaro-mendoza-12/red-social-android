package conectate.com.presentation.common

/** A suspension/read result belongs to one account and may force one exit only. */
internal class ForcedSessionExitGate(private val owner: String?, private val currentAccount: () -> String?) {
    private var handled = false
    fun accept(recipient: String? = owner): Boolean {
        if (handled || owner.isNullOrBlank() || !owner.equals(currentAccount(), ignoreCase = true) ||
            !owner.equals(recipient, ignoreCase = true)) return false
        handled = true
        return true
    }
}
