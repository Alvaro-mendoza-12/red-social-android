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

data class GiftReceipt(
    val transactionId: String,
    val liveStreamId: String?,
    val senderId: String,
    val senderName: String,
    val receiverId: String,
    val gift: VirtualGift,
    val senderBalanceDiamonds: Int,
    val isPublic: Boolean = true,
    val idempotentReplay: Boolean = false
)

data class LiveGiftEvent(
    val transactionId: String,
    val liveStreamId: String,
    val senderId: String,
    val senderName: String,
    val receiverId: String,
    val gift: VirtualGift,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class CoinProduct(
    val productId: String,
    val coins: Int
)

data class BillingCapabilities(
    val enabled: Boolean = false,
    val provider: String = "google_play",
    val products: List<CoinProduct> = emptyList()
)

data class CoinPurchaseVerification(
    val status: String,
    val credited: Boolean,
    val coins: Int,
    val balanceDiamonds: Int
)
