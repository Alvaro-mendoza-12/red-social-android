package conectate.com.data.localfallback

import conectate.com.data.model.LiveGiftEvent
import kotlinx.serialization.Serializable

@Serializable
internal data class LiveGiftEventsResponse(val items: List<LiveGiftEvent> = emptyList())
