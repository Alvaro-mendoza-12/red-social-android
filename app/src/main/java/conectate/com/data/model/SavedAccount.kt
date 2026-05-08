package conectate.com.data.model

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class SavedAccount(
    val userId: String,
    val displayName: String,
    val emailHint: String, // e.g., "a***@gmail.com"
    val avatarUrl: String? = null,
    val isOnline: Boolean = false,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val authToken: String? = null, // Encrypted refresh token
    val accountType: String = "email" // email, phone, google, apple
) {
    companion object {
        fun maskEmail(email: String): String {
            if (email.isBlank() || !email.contains("@")) return "***"
            val parts = email.split("@")
            val local = parts[0]
            val domain = parts[1]
            
            val maskedLocal = when {
                local.length <= 2 -> "*".repeat(local.length)
                local.length <= 4 -> local.take(1) + "*".repeat(local.length - 2) + local.takeLast(1)
                else -> local.take(2) + "***" + local.takeLast(2)
            }
            
            return "$maskedLocal@$domain"
        }
        
        fun maskPhone(phone: String): String {
            if (phone.length < 7) return "***"
            return phone.take(3) + "****" + phone.takeLast(2)
        }
    }
    
    val lastUsedRelative: String
        get() = runCatching {
            val now = System.currentTimeMillis()
            val elapsedMs = (now - lastUsedAt).coerceAtLeast(0L)
            when {
                elapsedMs < 60_000L -> "Usada hace un momento"
                elapsedMs < 3_600_000L -> "Usada hace ${elapsedMs / 60_000L} min"
                elapsedMs < 86_400_000L -> "Usada hace ${elapsedMs / 3_600_000L} h"
                else -> "Usada el ${Instant.ofEpochMilli(lastUsedAt)}"
            }
        }.getOrDefault("Cuenta guardada")
}

// Extension for list operations
fun List<SavedAccount>.sortedByLastUsed(): List<SavedAccount> {
    return sortedByDescending { it.lastUsedAt }
}

fun List<SavedAccount>.getById(userId: String): SavedAccount? {
    return find { it.userId == userId }
}
