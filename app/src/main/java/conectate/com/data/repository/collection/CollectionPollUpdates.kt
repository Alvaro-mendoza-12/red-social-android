package conectate.com.data.repository.collection

import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.supabase.AuthService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun collectionPollUpdates(
    realtime: UbuntuRealtimeClient,
    auth: AuthService,
    kind: String,
): Flow<String> = realtime.events.mapNotNull { event ->
    val account = auth.getCurrentUserId()?.trim()?.lowercase().orEmpty()
    if (account.isBlank() || event.channel != "user:$account" || event.event != "poll_updated" ||
        event.payload["scope_type"]?.jsonPrimitive?.contentOrNull != kind
    ) null else event.payload["poll_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
}
