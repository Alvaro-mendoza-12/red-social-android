package conectate.com.data.repository

import conectate.com.data.model.CreatorEarningSummary
import conectate.com.data.model.CreatorPayoutAccount
import conectate.com.data.model.CreatorProgramApplication
import conectate.com.data.model.CreatorProgramDashboard
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreatorProgramRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService
) {
    @Serializable
    private data class CreatorProgramRequest(
        @SerialName("user_id") val userId: String,
        val status: String,
        val niche: String?,
        @SerialName("portfolio_url") val portfolioUrl: String?,
        @SerialName("payout_country") val payoutCountry: String?,
        @SerialName("audience_size") val audienceSize: Int
    )

    @Serializable
    private data class PayoutAccountRequest(
        @SerialName("user_id") val userId: String,
        val provider: String,
        @SerialName("external_account_id") val externalAccountId: String?,
        val country: String?,
        val currency: String,
        val status: String = "pending"
    )

    suspend fun loadDashboard(): Result<CreatorProgramDashboard> = withContext(Dispatchers.IO) {
        runCatching {
            val userId = authService.getCurrentUserId() ?: throw IllegalStateException("No user logged in")
            val token = authService.getAccessToken() ?: throw IllegalStateException("No token")

            CreatorProgramDashboard(
                application = fetchApplication(token, userId),
                payoutAccount = fetchPayoutAccount(token, userId),
                earnings = fetchEarningsSummary(token, userId)
            )
        }
    }

    suspend fun submitApplication(
        niche: String,
        portfolioUrl: String?,
        payoutCountry: String?,
        audienceSize: Int
    ): Result<CreatorProgramApplication> = withContext(Dispatchers.IO) {
        runCatching {
            val userId = authService.getCurrentUserId() ?: throw IllegalStateException("No user logged in")
            val token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            val existing = fetchApplication(token, userId)
            val safeAudience = audienceSize.coerceAtLeast(0)
            val nextStatus = if (existing?.status == "approved") "approved" else "pending"

            if (existing == null) {
                val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/creator_program_applications") {
                    authHeaders(token)
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Prefer, "return=representation")
                    setBody(
                        CreatorProgramRequest(
                            userId = userId,
                            status = nextStatus,
                            niche = niche.cleanNullable(),
                            portfolioUrl = portfolioUrl.cleanNullable(),
                            payoutCountry = payoutCountry.cleanNullable(),
                            audienceSize = safeAudience
                        )
                    )
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("No se pudo enviar la solicitud: ${response.status}")
                }
                decodeObjects(response.bodyAsText()).first().toApplication()
            } else {
                val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/creator_program_applications") {
                    authHeaders(token)
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Prefer, "return=representation")
                    parameter("user_id", "eq.$userId")
                    setBody(buildJsonObject {
                        put("status", nextStatus)
                        put("niche", niche.cleanNullable())
                        put("portfolio_url", portfolioUrl.cleanNullable())
                        put("payout_country", payoutCountry.cleanNullable())
                        put("audience_size", safeAudience)
                        if (nextStatus == "pending") {
                            put("reviewed_by", JsonNull)
                            put("reviewed_at", JsonNull)
                            put("rejection_reason", JsonNull)
                        }
                    })
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("No se pudo actualizar la solicitud: ${response.status}")
                }
                decodeObjects(response.bodyAsText()).firstOrNull()?.toApplication()
                    ?: existing.copy(
                        status = nextStatus,
                        niche = niche.cleanNullable(),
                        portfolioUrl = portfolioUrl.cleanNullable(),
                        payoutCountry = payoutCountry.cleanNullable(),
                        audienceSize = safeAudience
                    )
            }
        }
    }

    suspend fun savePayoutAccount(
        provider: String,
        externalAccountId: String?,
        country: String?,
        currency: String
    ): Result<CreatorPayoutAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val userId = authService.getCurrentUserId() ?: throw IllegalStateException("No user logged in")
            val token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            val cleanProvider = provider.lowercase().trim().takeIf { it in setOf("stripe", "paypal", "bank", "manual") }
                ?: "manual"
            val cleanCurrency = currency.uppercase().trim().take(3).ifBlank { "USD" }
            val existing = fetchPayoutAccount(token, userId)

            if (existing == null) {
                val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/creator_payout_accounts") {
                    authHeaders(token)
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Prefer, "return=representation")
                    setBody(
                        PayoutAccountRequest(
                            userId = userId,
                            provider = cleanProvider,
                            externalAccountId = externalAccountId.cleanNullable(),
                            country = country.cleanNullable(),
                            currency = cleanCurrency
                        )
                    )
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("No se pudo guardar el método de pago: ${response.status}")
                }
                decodeObjects(response.bodyAsText()).first().toPayoutAccount()
            } else {
                val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/creator_payout_accounts") {
                    authHeaders(token)
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Prefer, "return=representation")
                    parameter("user_id", "eq.$userId")
                    setBody(buildJsonObject {
                        put("provider", cleanProvider)
                        put("external_account_id", externalAccountId.cleanNullable())
                        put("country", country.cleanNullable())
                        put("currency", cleanCurrency)
                        put("status", "pending")
                    })
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("No se pudo actualizar el método de pago: ${response.status}")
                }
                decodeObjects(response.bodyAsText()).firstOrNull()?.toPayoutAccount()
                    ?: existing.copy(
                        provider = cleanProvider,
                        externalAccountId = externalAccountId.cleanNullable(),
                        country = country.cleanNullable(),
                        currency = cleanCurrency,
                        status = "pending"
                    )
            }
        }
    }

    private suspend fun fetchApplication(token: String, userId: String): CreatorProgramApplication? {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/creator_program_applications") {
            authHeaders(token)
            parameter("user_id", "eq.$userId")
            parameter("select", "*")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return null
        return decodeObjects(response.bodyAsText()).firstOrNull()?.toApplication()
    }

    private suspend fun fetchPayoutAccount(token: String, userId: String): CreatorPayoutAccount? {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/creator_payout_accounts") {
            authHeaders(token)
            parameter("user_id", "eq.$userId")
            parameter("select", "*")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return null
        return decodeObjects(response.bodyAsText()).firstOrNull()?.toPayoutAccount()
    }

    private suspend fun fetchEarningsSummary(token: String, userId: String): CreatorEarningSummary {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/creator_earnings") {
            authHeaders(token)
            parameter("creator_id", "eq.$userId")
            parameter("select", "status,net_amount_credits")
        }
        if (!response.status.isSuccess()) return CreatorEarningSummary()

        var pending = 0.0
        var available = 0.0
        var paid = 0.0
        decodeObjects(response.bodyAsText()).forEach { row ->
            val amount = row.double("net_amount_credits")
            when (row.string("status")) {
                "pending" -> pending += amount
                "available" -> available += amount
                "paid" -> paid += amount
            }
        }
        return CreatorEarningSummary(
            pendingCredits = pending,
            availableCredits = available,
            paidCredits = paid,
            totalCredits = pending + available + paid
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders(token: String) {
        header("apikey", supabaseConfig.supabaseAnonKey)
        header("Authorization", "Bearer $token")
    }

    private fun decodeObjects(body: String): List<JsonObject> {
        if (body.isBlank()) return emptyList()
        return supabaseConfig.json.decodeFromString<JsonArray>(body).mapNotNull { it as? JsonObject }
    }

    private fun JsonObject.toApplication(): CreatorProgramApplication {
        return CreatorProgramApplication(
            id = string("id").orEmpty(),
            userId = string("user_id").orEmpty(),
            status = string("status") ?: "pending",
            niche = string("niche"),
            portfolioUrl = string("portfolio_url"),
            payoutCountry = string("payout_country"),
            audienceSize = int("audience_size"),
            rejectionReason = string("rejection_reason"),
            createdAt = string("created_at"),
            updatedAt = string("updated_at")
        )
    }

    private fun JsonObject.toPayoutAccount(): CreatorPayoutAccount {
        return CreatorPayoutAccount(
            userId = string("user_id").orEmpty(),
            provider = string("provider") ?: "manual",
            externalAccountId = string("external_account_id"),
            country = string("country"),
            currency = string("currency") ?: "USD",
            status = string("status") ?: "pending",
            createdAt = string("created_at"),
            updatedAt = string("updated_at")
        )
    }

    private fun JsonObject.string(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private fun JsonObject.double(key: String): Double {
        return this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
    }

    private fun String?.cleanNullable(): String? = this?.trim()?.takeIf { it.isNotBlank() }
}
