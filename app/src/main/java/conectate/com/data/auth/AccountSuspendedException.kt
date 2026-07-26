package conectate.com.data.auth

class AccountSuspendedException(
    val suspensionEndDate: String?,
    val reason: String?,
    override val message: String,
    val appealToken: String? = null,
    val decisionId: String? = null,
    val ruleTitle: String? = null,
    val explanation: String? = null,
    val evidence: List<String> = emptyList(),
    val appealDeadline: String? = null,
    val appealStatus: String? = null,
    val appealReviewerNote: String? = null
) : Exception(message) {
    companion object {
        const val ERROR_CODE = "ACCOUNT_SUSPENDED"
    }
}


