package conectate.com.data.repository

import android.util.Log
import conectate.com.data.model.AdCampaign
import conectate.com.data.model.MonetizationDashboard
import conectate.com.data.model.SubscriptionPlan
import conectate.com.data.model.UserSubscription
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

@Singleton
class MonetizationRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService
) {
    private companion object {
        private const val TAG = "MonetizationRepository"
    }

    suspend fun loadDashboard(): Result<MonetizationDashboard> = withContext(Dispatchers.IO) {
        runCatching {
            val userId = authService.getCurrentUserId()?.trim()
                ?: throw IllegalStateException("No hay una sesión activa")
            val token = authService.getAccessTokenAwait()
                ?: throw IllegalStateException("Sesión expirada. Inicia sesión de nuevo.")

            val plans = fetchSubscriptionPlans(token)
            val current = fetchCurrentSubscription(token, userId)
                ?.let { subscription ->
                    subscription.copy(planName = plans.firstOrNull { it.id == subscription.planId }?.name)
                }

            MonetizationDashboard(
                plans = plans,
                currentSubscription = current,
                activeAds = fetchActiveAdCampaigns(token)
            )
        }
    }

    suspend fun subscribeToPlan(planId: String): Result<UserSubscription> = withContext(Dispatchers.IO) {
        runCatching {
            val userId = authService.getCurrentUserId()?.trim()
                ?: throw IllegalStateException("No hay una sesión activa")
            val token = authService.getAccessTokenAwait()
                ?: throw IllegalStateException("Sesión expirada. Inicia sesión de nuevo.")
            val cleanPlanId = planId.trim()
            if (cleanPlanId.isBlank()) {
                throw IllegalArgumentException("Selecciona un plan válido")
            }

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/subscribe_to_plan") {
                authHeaders(token)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Prefer, "return=representation")
                setBody(buildJsonObject {
                    put("p_user_id", userId)
                    put("p_plan_id", cleanPlanId)
                    put("p_payment_method", "wallet")
                })
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw IllegalStateException(
                    parseSupabaseError(body) ?: "No se pudo activar Premium (${response.status.value})"
                )
            }

            decodeObjects(body).firstOrNull()?.toSubscriptionFromRpc(userId, cleanPlanId)
                ?: fetchCurrentSubscription(token, userId)
                ?: throw IllegalStateException("Premium se procesó, pero no se pudo leer la suscripción")
        }
    }

    suspend fun recordAdImpression(campaignId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanCampaignId = campaignId.trim()
            if (cleanCampaignId.isBlank()) return@runCatching false
            val userId = authService.getCurrentUserId()?.trim().orEmpty()
            val token = authService.getAccessTokenAwait() ?: return@runCatching false

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/ad_impressions") {
                authHeaders(token)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Prefer, "return=minimal")
                setBody(buildJsonObject {
                    put("campaign_id", cleanCampaignId)
                    if (userId.isNotBlank()) {
                        put("user_id", userId)
                    } else {
                        put("user_id", JsonNull)
                    }
                })
            }
            if (!response.status.isSuccess()) {
                Log.w(TAG, "No se pudo registrar impresión de anuncio: ${response.status}")
            }
            response.status.isSuccess()
        }
    }

    private suspend fun fetchSubscriptionPlans(token: String): List<SubscriptionPlan> {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/subscription_plans") {
            authHeaders(token)
            parameter("is_active", "eq.true")
            parameter("select", "id,name,description,price_monthly,price_yearly,features,is_active,sort_order")
            parameter("order", "sort_order.asc,name.asc")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("No se pudieron cargar planes Premium: ${response.status}")
        }
        return decodeObjects(response.bodyAsText())
            .map { it.toSubscriptionPlan() }
            .filter { it.id.isNotBlank() && it.isActive }
            .sortedWith(compareBy<SubscriptionPlan> { it.sortOrder }.thenBy { it.name })
    }

    private suspend fun fetchCurrentSubscription(token: String, userId: String): UserSubscription? {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/user_subscriptions") {
            authHeaders(token)
            parameter("user_id", "eq.$userId")
            parameter("status", "eq.active")
            parameter(
                "select",
                "id,user_id,plan_id,status,current_period_start,current_period_end,cancel_at_period_end"
            )
            parameter("order", "current_period_end.desc")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return null
        return decodeObjects(response.bodyAsText()).firstOrNull()?.toUserSubscription()
    }

    private suspend fun fetchActiveAdCampaigns(token: String, limit: Int = 8): List<AdCampaign> {
        val now = Instant.now().toString()
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/ad_campaigns") {
            authHeaders(token)
            parameter("status", "eq.active")
            parameter("start_date", "lte.$now")
            parameter("end_date", "gte.$now")
            parameter(
                "select",
                "id,advertiser_id,name,creative_url,target_url,budget,spent,start_date,end_date,status,targeting_hashtags,targeting_countries"
            )
            parameter("order", "created_at.desc")
            parameter("limit", limit.coerceIn(1, 24).toString())
        }
        if (!response.status.isSuccess()) {
            Log.w(TAG, "No se pudieron cargar anuncios activos: ${response.status}")
            return emptyList()
        }
        return decodeObjects(response.bodyAsText())
            .map { it.toAdCampaign() }
            .filter { it.id.isNotBlank() && it.status == "active" }
    }

    private fun HttpRequestBuilder.authHeaders(token: String) {
        header("apikey", supabaseConfig.supabaseAnonKey)
        header("Authorization", "Bearer $token")
    }

    private fun decodeObjects(body: String): List<JsonObject> {
        if (body.isBlank()) return emptyList()
        return runCatching {
            when (val parsed = supabaseConfig.json.parseToJsonElement(body)) {
                is JsonArray -> parsed.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(parsed)
                else -> emptyList()
            }
        }.getOrElse { error ->
            Log.e(TAG, "Error decodificando JSON de monetización", error)
            emptyList()
        }
    }

    private fun JsonObject.toSubscriptionPlan(): SubscriptionPlan {
        return SubscriptionPlan(
            id = string("id").orEmpty(),
            name = string("name").orEmpty(),
            description = string("description"),
            priceMonthly = double("price_monthly"),
            priceYearly = double("price_yearly"),
            features = stringList("features"),
            sortOrder = int("sort_order"),
            isActive = boolean("is_active") ?: true
        )
    }

    private fun JsonObject.toUserSubscription(): UserSubscription {
        return UserSubscription(
            id = string("id").orEmpty(),
            userId = string("user_id").orEmpty(),
            planId = string("plan_id").orEmpty(),
            status = string("status") ?: "active",
            currentPeriodStart = string("current_period_start"),
            currentPeriodEnd = string("current_period_end"),
            cancelAtPeriodEnd = boolean("cancel_at_period_end") ?: false
        )
    }

    private fun JsonObject.toSubscriptionFromRpc(userId: String, planId: String): UserSubscription {
        return UserSubscription(
            id = string("subscription_id").orEmpty(),
            userId = userId,
            planId = planId,
            planName = string("plan_name"),
            status = "active",
            currentPeriodStart = string("current_period_start"),
            currentPeriodEnd = string("current_period_end")
        )
    }

    private fun JsonObject.toAdCampaign(): AdCampaign {
        return AdCampaign(
            id = string("id").orEmpty(),
            advertiserId = string("advertiser_id").orEmpty(),
            name = string("name").orEmpty(),
            creativeUrl = string("creative_url").orEmpty(),
            targetUrl = string("target_url").orEmpty(),
            budget = double("budget"),
            spent = double("spent"),
            startDate = string("start_date"),
            endDate = string("end_date"),
            status = string("status") ?: "active",
            targetingHashtags = stringList("targeting_hashtags"),
            targetingCountries = stringList("targeting_countries")
        )
    }

    private fun JsonObject.string(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JsonObject.int(key: String): Int {
        return this[key]?.jsonPrimitive?.intOrNull
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
            ?: 0
    }

    private fun JsonObject.double(key: String): Double {
        return this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        return this[key]?.jsonPrimitive?.booleanOrNull
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    }

    private fun JsonObject.stringList(key: String): List<String> {
        return when (val element = this[key]) {
            is JsonArray -> element.mapNotNull { it.asCleanString() }
            is JsonElement -> runCatching {
                supabaseConfig.json.parseToJsonElement(element.jsonPrimitive.content).jsonArray
                    .mapNotNull { it.asCleanString() }
            }.getOrElse {
                element.asCleanString()?.let(::listOf).orEmpty()
            }
            else -> emptyList()
        }
    }

    private fun JsonElement.asCleanString(): String? {
        if (this is JsonNull) return null
        return jsonPrimitive.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun parseSupabaseError(body: String): String? {
        return runCatching {
            val obj = supabaseConfig.json.parseToJsonElement(body) as? JsonObject
            obj?.string("message") ?: obj?.string("details") ?: obj?.string("hint")
        }.getOrNull()
    }
}

fun SubscriptionPlan.monthlyPriceInDiamonds(): Int {
    return ceil(priceMonthly * 10.0).toInt().coerceAtLeast(0)
}
