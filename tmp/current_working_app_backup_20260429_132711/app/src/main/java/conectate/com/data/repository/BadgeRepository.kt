package conectate.com.data.repository

import conectate.com.data.model.Badge
import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BadgeRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig
) {
    private val client = supabaseConfig.client

    /**
     * Get all available badges
     */
    suspend fun getAllBadges(): Result<List<Badge>> {
        return try {
            var badges = client.from("badges")
                .select()
                .decodeList<Badge>()
            Result.success(badges)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get badges for a specific user
     */
    suspend fun getUserBadges(userId: String): Result<List<Badge>> {
        return try {
            var result = client.postgrest.rpc(
                "get_user_badges",
                buildJsonObject {
                    put("p_user_id", userId)
                }
            )
            
            var badges = result.decodeList<Badge>()
            Result.success(badges)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create a custom badge (admin only)
     */
    suspend fun createCustomBadge(
        name: String,
        description: String,
        icon: String,
        color: String,
        createdBy: String
    ): Result<String> {
        return try {
            var result = client.postgrest.rpc(
                "create_custom_badge",
                buildJsonObject {
                    put("p_name", name)
                    put("p_description", description)
                    put("p_icon", icon)
                    put("p_color", color)
                    put("p_created_by", createdBy)
                }
            )
            
            // Returns badge ID
            val badgeId = result.decodeAs<String>()
            Result.success(badgeId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Grant a badge to a user (admin only)
     */
    suspend fun grantBadge(
        userId: String,
        badgeId: String,
        grantedBy: String
    ): Result<Boolean> {
        return try {
            var result = client.postgrest.rpc(
                "grant_badge_to_user",
                buildJsonObject {
                    put("p_user_id", userId)
                    put("p_badge_id", badgeId)
                    put("p_granted_by", grantedBy)
                }
            )
            
            var success = result.decodeAs<Boolean>()
            Result.success(success)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Revoke a badge from a user (admin only)
     */
    suspend fun revokeBadge(
        userId: String,
        badgeId: String,
        revokedBy: String
    ): Result<Boolean> {
        return try {
            var result = client.postgrest.rpc(
                "revoke_badge_from_user",
                buildJsonObject {
                    put("p_user_id", userId)
                    put("p_badge_id", badgeId)
                    put("p_revoked_by", revokedBy)
                }
            )
            
            var success = result.decodeAs<Boolean>()
            Result.success(success)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set verified status for a user (admin only)
     */
    suspend fun setVerified(
        userId: String,
        verified: Boolean,
        setBy: String
    ): Result<Boolean> {
        return try {
            var result = client.postgrest.rpc(
                "set_user_verified",
                buildJsonObject {
                    put("p_user_id", userId)
                    put("p_verified", verified)
                    put("p_set_by", setBy)
                }
            )
            
            var success = result.decodeAs<Boolean>()
            Result.success(success)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get all badge assignments (admin only)
     */
    suspend fun getBadgeAssignments(): Result<List<conectate.com.data.model.BadgeAssignment>> {
        return try {
            val assignments = client.from("user_badges")
                .select(Columns.raw("*, user:profiles(*), badge:badges(*)"))
                .decodeList<conectate.com.data.model.BadgeAssignment>()
            Result.success(assignments)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}



