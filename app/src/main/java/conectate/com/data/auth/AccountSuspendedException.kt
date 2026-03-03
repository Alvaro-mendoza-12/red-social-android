package conectate.com.data.auth

class AccountSuspendedException(
    val suspensionEndDate: String?,
    val reason: String?,
    override val message: String
) : Exception(message) {
    companion object {
        const val ERROR_CODE = "ACCOUNT_SUSPENDED"
    }
}
