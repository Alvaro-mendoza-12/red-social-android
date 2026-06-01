package conectate.com.data.model

data class WalletBalance(
    val userId: String,
    val balanceDiamonds: Int = 0,
    val balanceCredits: Double = 0.0,
    val totalSpent: Double = 0.0
)

data class VirtualGift(
    val id: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val priceDiamonds: Int = 0,
    val priceCredits: Double = 0.0,
    val animationUrl: String? = null,
    val sortOrder: Int = 0
)
