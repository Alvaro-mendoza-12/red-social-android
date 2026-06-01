package conectate.com.data.model

data class SubscriptionPlan(
    val id: String,
    val name: String,
    val description: String? = null,
    val priceMonthly: Double = 0.0,
    val priceYearly: Double = 0.0,
    val features: List<String> = emptyList(),
    val sortOrder: Int = 0,
    val isActive: Boolean = true
)

data class UserSubscription(
    val id: String,
    val userId: String,
    val planId: String,
    val planName: String? = null,
    val status: String,
    val currentPeriodStart: String? = null,
    val currentPeriodEnd: String? = null,
    val cancelAtPeriodEnd: Boolean = false
)

data class AdCampaign(
    val id: String,
    val advertiserId: String,
    val name: String,
    val creativeUrl: String,
    val targetUrl: String,
    val budget: Double = 0.0,
    val spent: Double = 0.0,
    val startDate: String? = null,
    val endDate: String? = null,
    val status: String = "active",
    val targetingHashtags: List<String> = emptyList(),
    val targetingCountries: List<String> = emptyList()
)

data class MonetizationDashboard(
    val plans: List<SubscriptionPlan> = emptyList(),
    val currentSubscription: UserSubscription? = null,
    val activeAds: List<AdCampaign> = emptyList()
)
