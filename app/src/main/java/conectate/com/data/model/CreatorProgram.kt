package conectate.com.data.model

import java.math.BigDecimal

data class CreatorProgramApplication(
    val id: String,
    val userId: String,
    val status: String = "pending",
    val niche: String? = null,
    val portfolioUrl: String? = null,
    val payoutCountry: String? = null,
    val audienceSize: Int = 0,
    val rejectionReason: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class CreatorPayoutAccount(
    val userId: String,
    val provider: String,
    val externalAccountId: String? = null,
    val country: String? = null,
    val currency: String = "USD",
    val status: String = "pending",
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class CreatorEarningSummary(
    val pendingCredits: BigDecimal = BigDecimal.ZERO,
    val availableCredits: BigDecimal = BigDecimal.ZERO,
    val heldCredits: BigDecimal = BigDecimal.ZERO,
    val reservedCredits: BigDecimal = BigDecimal.ZERO,
    val paidCredits: BigDecimal = BigDecimal.ZERO,
    val refundedCredits: BigDecimal = BigDecimal.ZERO,
    val totalCredits: BigDecimal = BigDecimal.ZERO,
    /** Raw purchased coins received as gifts; kept separate from fiat credits. */
    val pendingCoins: Int = 0,
    val availableCoins: Int = 0,
    val heldCoins: Int = 0,
    val paidCoins: Int = 0,
    val totalCoins: Int = 0,
)

data class CreatorEarningActivity(
    val id: String,
    val sourceType: String,
    val status: String,
    val grossCredits: BigDecimal = BigDecimal.ZERO,
    val platformFeeCredits: BigDecimal = BigDecimal.ZERO,
    val taxWithheldCredits: BigDecimal = BigDecimal.ZERO,
    val creatorShareCredits: BigDecimal = BigDecimal.ZERO,
    val netCredits: BigDecimal = BigDecimal.ZERO,
    val amountCoins: Int = 0,
    val createdAt: String? = null,
    val availableAt: String? = null,
    val paidAt: String? = null
)

data class CreatorProgramDashboard(
    val application: CreatorProgramApplication? = null,
    val payoutAccount: CreatorPayoutAccount? = null,
    val earnings: CreatorEarningSummary = CreatorEarningSummary(),
    val activity: List<CreatorEarningActivity> = emptyList(),
    val nextPayoutAt: String? = null
)
