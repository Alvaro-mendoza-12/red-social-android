package conectate.com.data.repository.collection

data class CollectionGovernancePrivileges(
    val currentUserId: String,
    val creatorId: String,
    val memberRole: String?,
    val isGlobalAdmin: Boolean
) {
    val isCreator: Boolean
        get() = currentUserId == creatorId

    val isElevatedMember: Boolean
        get() = memberRole == "admin" || memberRole == "owner"

    val canManageMembers: Boolean
        get() = isCreator || isElevatedMember

    val canManageCollection: Boolean
        get() = isCreator || isElevatedMember
}

object CollectionGovernanceSupport {
    fun normalizeId(rawId: String): String = rawId.trim().lowercase()

    fun normalizeRole(role: String?): String? =
        role?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    suspend fun resolvePrivileges(
        currentUserId: String,
        creatorId: String,
        loadMemberRole: suspend (scopeUserId: String) -> String?,
        loadIsGlobalAdmin: suspend (scopeUserId: String) -> Boolean = { false }
    ): CollectionGovernancePrivileges {
        val normalizedCurrentUserId = normalizeId(currentUserId)
        val normalizedCreatorId = normalizeId(creatorId)
        return CollectionGovernancePrivileges(
            currentUserId = normalizedCurrentUserId,
            creatorId = normalizedCreatorId,
            memberRole = normalizeRole(loadMemberRole(normalizedCurrentUserId)),
            isGlobalAdmin = loadIsGlobalAdmin(normalizedCurrentUserId)
        )
    }

    fun requireTargetIsNotCreator(
        targetUserId: String,
        creatorId: String,
        errorMessage: String
    ) {
        if (normalizeId(targetUserId) == normalizeId(creatorId)) {
            throw IllegalStateException(errorMessage)
        }
    }

    fun requireCanManageMembers(
        privileges: CollectionGovernancePrivileges,
        errorMessage: String
    ) {
        if (!privileges.canManageMembers) {
            throw IllegalStateException(errorMessage)
        }
    }

    fun requireCanManageCollection(
        privileges: CollectionGovernancePrivileges,
        errorMessage: String
    ) {
        if (!privileges.canManageCollection) {
            throw IllegalStateException(errorMessage)
        }
    }
}


