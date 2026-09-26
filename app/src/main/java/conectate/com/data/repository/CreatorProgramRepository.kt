package conectate.com.data.repository

import conectate.com.data.model.CreatorEarningSummary
import conectate.com.data.model.CreatorEarningActivity
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
import java.math.BigDecimal
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

            val earnings = fetchEarnings(token, userId)
            CreatorProgramDashboard(
                application = fetchApplication(token, userId),
                payoutAccount = fetchPayoutAccount(token, userId),
                earnings = earnings.summary,
                activity = earnings.activity,
                nextPayoutAt = earnings.nextPayoutAt
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
                        put("niche", niche.cleanNullable())
                        put("portfolio_url", portfolioUrl.cleanNullable())
                        put("payout_country", payoutCountry.cleanNullable())
                        put("audience_size", safeAudience)
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
            val safeExternalAccountId = externalAccountId.safeProviderReference()
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
                            externalAccountId = safeExternalAccountId,
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
                        put("external_account_id", safeExternalAccountId)
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
                        externalAccountId = safeExternalAccountId,
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

    private data class EarningsSnapshot(
        val summary: CreatorEarningSummary = CreatorEarningSummary(),
        val activity: List<CreatorEarningActivity> = emptyList(),
        val nextPayoutAt: String? = null
    )

    private suspend fun fetchEarnings(token: String, userId: String): EarningsSnapshot {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/creator_earnings") {
            authHeaders(token)
            parameter("creator_id", "eq.$userId")
            parameter("select", "*")
            parameter("order", "created_at.desc")
            parameter("limit", "100")
        }
        if (!response.status.isSuccess()) return EarningsSnapshot()

        var pending = BigDecimal.ZERO
        var available = BigDecimal.ZERO
        var held = BigDecimal.ZERO
        var reserved = BigDecimal.ZERO
        var paid = BigDecimal.ZERO
        var refunded = BigDecimal.ZERO
        var pendingCoins = 0
        var availableCoins = 0
        var heldCoins = 0
        var paidCoins = 0
        var refundedCoins = 0
        val activity = decodeObjects(response.bodyAsText()).mapNotNull { row ->
            val id = row.string("id").orEmpty()
            if (id.isBlank()) return@mapNotNull null
            val amount = row.bigDecimalAny("net_amount_credits", "net_amount", "amount_credits")
            val status = row.string("status")?.lowercase().orEmpty()
            val coins = row.intAny("withdrawable_amount_diamonds", "amount_diamonds").coerceAtLeast(0)
            when (status) {
                "pending" -> {
                    pending += amount
                    pendingCoins += coins
                }
                "available" -> {
                    available += amount
                    availableCoins += coins
                }
                "held", "in_hold", "under_review" -> {
                    held += amount
                    heldCoins += coins
                }
                "reserved" -> {
                    reserved += amount
                    heldCoins += coins
                }
                "paid" -> {
                    paid += amount
                    paidCoins += coins
                }
                "refunded", "reversed" -> {
                    refunded += amount
                    refundedCoins += coins
                }
            }
            CreatorEarningActivity(
                id = id,
                sourceType = row.string("source_type") ?: "virtual_gift",
                status = row.string("status") ?: "pending",
                grossCredits = row.bigDecimalAny("gross_amount_credits", "gross_amount", "amount_credits"),
                platformFeeCredits = row.bigDecimalAny("platform_fee_credits", "platform_fee"),
                taxWithheldCredits = row.bigDecimalAny("tax_withheld_credits", "tax_withheld"),
                creatorShareCredits = row.bigDecimalAny("creator_share_credits", "creator_share"),
                netCredits = amount,
                amountCoins = coins,
                createdAt = row.string("created_at"),
                availableAt = row.string("available_at"),
                paidAt = row.string("paid_at")
            )
        }
        return EarningsSnapshot(
            summary = CreatorEarningSummary(
                pendingCredits = pending,
                availableCredits = available,
                heldCredits = held,
                reservedCredits = reserved,
                paidCredits = paid,
                refundedCredits = refunded,
                totalCredits = pending + available + held + reserved + paid - refunded,
                pendingCoins = pendingCoins,
                availableCoins = availableCoins,
                heldCoins = heldCoins,
                paidCoins = paidCoins,
                totalCoins = (pendingCoins + availableCoins + heldCoins + paidCoins - refundedCoins).coerceAtLeast(0),
            ),
            activity = activity,
            nextPayoutAt = activity.asSequence()
                .filter { it.status.lowercase() in setOf("pending", "held", "reserved") }
                .mapNotNull { it.availableAt }
                .minOrNull()
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

    private fun JsonObject.intAny(vararg keys: String): Int {
        val key = keys.firstOrNull { containsKey(it) } ?: return 0
        return int(key)
    }

    private fun JsonObject.bigDecimal(key: String): BigDecimal {
        return this[key]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO
    }

    private fun JsonObject.bigDecimalAny(vararg keys: String): BigDecimal {
        val present = keys.firstOrNull { containsKey(it) } ?: return BigDecimal.ZERO
        return bigDecimal(present)
    }

    private fun String?.cleanNullable(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun String?.safeProviderReference(): String? {
        val clean = cleanNullable() ?: return null
        val digitsOnly = clean.filter(Char::isDigit)
        require(!(digitsOnly.length in 12..19 && clean.all { it.isDigit() || it == ' ' || it == '-' })) {
            "Usa únicamente el identificador tokenizado del proveedor; no ingreses PAN ni número de tarjeta."
        }
        require(!(digitsOnly.length in 3..4 && clean.all(Char::isDigit))) {
            "No ingreses códigos CVV."
        }
        return clean
    }
}
