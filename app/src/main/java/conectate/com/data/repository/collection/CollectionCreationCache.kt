package conectate.com.data.repository.collection

import conectate.com.data.local.entity.*
import conectate.com.data.localfallback.LocalFallbackCreateCollectionResponse
import conectate.com.data.model.Community
import conectate.com.data.model.Group
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

private val creationJson = Json { ignoreUnknownKeys = true }

/** Project the confirmed room and current memberships, never the older submitted draft. */
internal fun collectionCreationGroupCache(receipt: LocalFallbackCreateCollectionResponse, owner: String): Pair<GroupEntity, List<GroupMemberEntity>> {
    val room = creationJson.decodeFromJsonElement<Group>(requireNotNull(receipt.room))
    val members = requireNotNull(receipt.initialMembers).map { member ->
        val creator = member.user_id.equals(room.creator_id, true)
        GroupMemberEntity(room.id, if (creator) owner else member.user_id, if (creator) "owner" else member.role,
            member.joined_at.ifBlank { room.created_at })
    }.distinctBy { it.user_id }
    return GroupEntity.fromDomain(room.copy(creator_id = owner, is_member = true)) to members
}

internal fun collectionCreationCommunityCache(receipt: LocalFallbackCreateCollectionResponse, owner: String): Pair<CommunityEntity, List<CommunityMemberEntity>> {
    val room = creationJson.decodeFromJsonElement<Community>(requireNotNull(receipt.room))
    val members = requireNotNull(receipt.initialMembers).map { member ->
        val creator = member.user_id.equals(room.creator_id, true)
        CommunityMemberEntity(room.id, if (creator) owner else member.user_id, if (creator) "owner" else member.role,
            member.joined_at.ifBlank { room.created_at })
    }.distinctBy { it.user_id }
    return CommunityEntity.fromDomain(room.copy(creator_id = owner, is_member = true, current_user_role = "owner")) to members
}
