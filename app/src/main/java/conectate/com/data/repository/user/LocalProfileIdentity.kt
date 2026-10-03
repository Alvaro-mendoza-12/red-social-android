package conectate.com.data.repository.user

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class LocalProfileIdentity(val numericId: Long?, val usernameOrigin: String?)

internal fun readLocalProfileIdentity(profile: JsonObject): LocalProfileIdentity = LocalProfileIdentity(
    numericId = (profile["numeric_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
    usernameOrigin = (profile["username_origin"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it == "chosen" || it == "migrated_name" },
)
