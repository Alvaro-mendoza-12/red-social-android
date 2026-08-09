package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CreatorPayoutAccountSummary(
    val provider: String = "paypal",
    val emailMasked: String? = null,
    val status: String = "PENDING",
    val currency: String = "USD",
    val verifiedAt: String? = null,
)

@Serializable
data class CreatorPayoutBalances(
    val pendingMinor: Long = 0,
    val availableMinor: Long = 0,
    val heldMinor: Long = 0,
    val paidMinor: Long = 0,
    val recoveryDebtMinor: Long = 0,
)

@Serializable
data class CreatorPayoutRequest(
    val id: String,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val account: String? = null,
    val failureCode: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val approvedAt: String? = null,
    val paidAt: String? = null,
)

@Serializable
data class CreatorPayoutSummary(
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val mode: String = "sandbox",
    val creatorVerified: Boolean = false,
    val currency: String = "USD",
    val minimumMinor: Long = 0,
    val holdDays: Int = 7,
    val account: CreatorPayoutAccountSummary? = null,
    val balances: CreatorPayoutBalances = CreatorPayoutBalances(),
    val requests: List<CreatorPayoutRequest> = emptyList(),
)

@Serializable
data class CreatorPayoutAccountResponse(
    val account: CreatorPayoutAccountSummary,
)

@Serializable
data class CreatorPayoutRequestResponse(
    val request: CreatorPayoutRequest,
    val duplicate: Boolean = false,
)
