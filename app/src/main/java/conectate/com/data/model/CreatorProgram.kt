package conectate.com.data.model

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
    val pendingCredits: Double = 0.0,
    val availableCredits: Double = 0.0,
    val paidCredits: Double = 0.0,
    val totalCredits: Double = 0.0
)

data class CreatorProgramDashboard(
    val application: CreatorProgramApplication? = null,
    val payoutAccount: CreatorPayoutAccount? = null,
    val earnings: CreatorEarningSummary = CreatorEarningSummary()
)
