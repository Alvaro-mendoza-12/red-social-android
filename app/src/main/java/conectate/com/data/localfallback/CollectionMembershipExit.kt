package conectate.com.data.localfallback

import kotlinx.serialization.Serializable

@Serializable
data class CollectionMembershipExit(val ok: Boolean = false, val historyAccess: CollectionHistoryAccess? = null)
