package conectate.com.data.repository.message

import android.content.Context
import android.net.Uri
import conectate.com.core.moderation.ContentModerationService
import conectate.com.core.moderation.ModerationContext
import conectate.com.core.moderation.ModerationVerdict
import conectate.com.core.observability.AppMetrics
import conectate.com.core.security.GuardedAction
import conectate.com.core.security.GuardOutcome
import conectate.com.core.security.InputSecurityPolicy
import conectate.com.core.security.OperationGuard
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.model.Report
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.util.uploadReportEvidence
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class MessageReportStore(
    private val context: Context,
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val storageService: StorageService,
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val userRepository: UserRepository,
    private val moderationService: ContentModerationService,
    private val appMetrics: AppMetrics,
    private val operationGuard: OperationGuard
) {
    private var json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun submitReport(
        targetId: String,
        name: String,
        reason: String,
        desc: String,
        uri: Uri?
    ): Result<Unit> {
        var auditUserId: String? = null
        var uploadedEvidenceKey: String? = null
        var reportPersisted = false
        val startedAt = System.currentTimeMillis()
        return try {
            val currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user logged in"))
            val cleanUserId = currentUserId.trim().lowercase()
            auditUserId = cleanUserId
            val cleanTargetId = targetId.trim().lowercase()

            val guardDecision = operationGuard.acquire(
                action = GuardedAction.REPORT_SUBMISSION,
                actorId = cleanUserId,
                scopeId = cleanTargetId
            )
            if (!guardDecision.allowed) {
                val retryAfterSeconds = InputSecurityPolicy.retryAfterSeconds(guardDecision.retryAfterMs)
                return Result.failure(Exception("Has enviado demasiados reportes. Intenta en ${retryAfterSeconds}s."))
            }

            var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val baseReason = InputSecurityPolicy.sanitizeReason(reason)
            val baseDescription = InputSecurityPolicy.sanitizeDescription(desc)
            val moderatedReason = moderationService.moderate(baseReason, ModerationContext.REPORT_REASON)
            val moderatedDescription = moderationService.moderate(baseDescription, ModerationContext.REPORT_DESCRIPTION)
            if (moderatedReason.verdict == ModerationVerdict.BLOCK || moderatedDescription.verdict == ModerationVerdict.BLOCK) {
                appMetrics.recordFailure(
                    metric = "report_submit",
                    attributes = mapOf("reason" to "moderation_block")
                )
                return Result.failure(Exception("El reporte contiene contenido no permitido"))
            }
            val sanitizedReason = moderatedReason.sanitizedText
            val sanitizedDescription = moderatedDescription.sanitizedText
            val sanitizedName = name.trim().take(80)
            if (sanitizedReason.isBlank()) {
                return Result.failure(Exception("El motivo del reporte es obligatorio"))
            }

            var evidenceUrl: String? = null
            if (uri != null) {
                val uploadedEvidence = uploadReportEvidence(
                    context = context,
                    storageService = storageService,
                    userId = cleanUserId,
                    sourceUri = uri
                ).getOrElse { error -> return Result.failure(error) }
                uploadedEvidenceKey = uploadedEvidence.objectKey
                evidenceUrl = uploadedEvidence.publicUrl
            }

            val currentUser = userRepository.getUserById(cleanUserId).getOrNull()
            val reporterName = currentUser?.username ?: currentUser?.display_name ?: "Usuario"
            android.util.Log.d("MessageReportStore", "Submitting moderated user report")

            if (supabaseConfig.isLocalFallbackPrimary) {
                val result = localFallbackApiClient.submitUserReport(
                    accessToken = token,
                    reportedUserId = cleanTargetId,
                    reportedUserName = sanitizedName,
                    reason = sanitizedReason,
                    description = sanitizedDescription,
                    evidenceUrl = evidenceUrl
                )
                if (result.isSuccess) {
                    reportPersisted = true
                    runCatching {
                        operationGuard.audit(
                            action = GuardedAction.REPORT_SUBMISSION,
                            actorId = cleanUserId,
                            scopeId = cleanTargetId,
                            outcome = GuardOutcome.SUCCESS
                        )
                    }
                    appMetrics.incrementCounter("report_submit_success", attributes = mapOf("backend" to "ubuntu"))
                    appMetrics.recordLatency(
                        metric = "report_submit",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = mapOf("backend" to "ubuntu")
                    )
                } else {
                    runCatching {
                        operationGuard.audit(
                            action = GuardedAction.REPORT_SUBMISSION,
                            actorId = cleanUserId,
                            scopeId = cleanTargetId,
                            outcome = GuardOutcome.FAILED,
                            attributes = mapOf("backend" to "ubuntu")
                        )
                    }
                    appMetrics.recordFailure(
                        metric = "report_submit",
                        throwable = result.exceptionOrNull(),
                        attributes = mapOf("backend" to "ubuntu")
                    )
                }
                return result
            }

            var payload = mutableMapOf<String, String?>()
            payload["reporter_id"] = cleanUserId
            payload["reporter_name"] = reporterName
            payload["reported_user_id"] = cleanTargetId
            payload["reported_user_name"] = sanitizedName
            payload["reason"] = sanitizedReason
            payload["description"] = sanitizedDescription
            payload["evidence_url"] = evidenceUrl
            payload["status"] = "pendiente"

            var usedLegacyPath = false
            var response = run {
                val rpcPayload = buildJsonObject {
                    put("p_reported_user_id", cleanTargetId)
                    put("p_reported_user_name", sanitizedName)
                    put("p_reason", sanitizedReason)
                    put("p_description", sanitizedDescription)
                    put("p_evidence_url", evidenceUrl)
                    put("p_reporter_name", reporterName)
                }

                val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_submit_report") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    header("Prefer", "return=representation")
                    setBody(rpcPayload)
                }

                val rpcError = if (!rpcResponse.status.isSuccess()) {
                    runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
                } else {
                    ""
                }

                if (!rpcResponse.status.isSuccess() &&
                    isMissingRpcFunction(rpcResponse.status.value, rpcError, "secure_submit_report")
                ) {
                    usedLegacyPath = true
                    supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/reports") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        header("Prefer", "return=representation")
                        setBody(payload)
                    }
                } else {
                    rpcResponse
                }
            }

            if (response.status.isSuccess()) {
                reportPersisted = true
                android.util.Log.d("MessageReportStore", "Report persisted (${if (usedLegacyPath) "legacy" else "rpc"})")
                runCatching {
                    operationGuard.audit(
                        action = GuardedAction.REPORT_SUBMISSION,
                        actorId = cleanUserId,
                        scopeId = cleanTargetId,
                        outcome = GuardOutcome.SUCCESS
                    )
                }
                appMetrics.incrementCounter("report_submit_success")
                appMetrics.recordLatency(
                    metric = "report_submit",
                    durationMs = System.currentTimeMillis() - startedAt
                )
                Result.success(Unit)
            } else {
                val err = runCatching { response.bodyAsText() }.getOrDefault("")
                android.util.Log.e("MessageReportStore", "submitReport FAILED: ${response.status} - $err")
                runCatching {
                    operationGuard.audit(
                        action = GuardedAction.REPORT_SUBMISSION,
                        actorId = cleanUserId,
                        scopeId = cleanTargetId,
                        outcome = GuardOutcome.FAILED,
                        attributes = mapOf("http_status" to response.status.value.toString())
                    )
                }
                appMetrics.recordFailure(
                    metric = "report_submit",
                    attributes = mapOf("http_status" to response.status.value.toString())
                )
                Result.failure(Exception("Error al enviar reporte: $err"))
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageReportStore", "Error submitting report", e)
            var actorId = auditUserId
            if (!actorId.isNullOrBlank()) {
                runCatching {
                    operationGuard.audit(
                        action = GuardedAction.REPORT_SUBMISSION,
                        actorId = actorId,
                        scopeId = targetId.trim().lowercase(),
                        outcome = GuardOutcome.FAILED,
                        attributes = mapOf("error_type" to (e::class.simpleName ?: "unknown"))
                    )
                }
            }
            appMetrics.recordFailure("report_submit", e)
            Result.failure(e)
        } finally {
            if (!reportPersisted) {
                uploadedEvidenceKey?.let { key ->
                    storageService.deleteFile("report-evidence", key).onFailure { cleanupError ->
                        android.util.Log.w("MessageReportStore", "Could not clean report evidence", cleanupError)
                    }
                }
            }
        }
    }

    suspend fun getMyReports(): Result<List<Report>> {
        var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
        val userId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
        return fetchReports(
            token = token,
            logLabel = "getMyReports",
            reporterId = userId.trim().lowercase()
        )
    }

    suspend fun getAllReports(): Result<List<Report>> {
        var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
        return fetchReports(
            token = token,
            logLabel = "getAllReports",
            reporterId = null
        )
    }

    suspend fun updateReportStatus(reportId: String, newStatus: String): Result<Unit> {
        return try {
            var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val update = buildJsonObject { put("status", newStatus) }
            supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/reports") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter("id", "eq.$reportId")
                setBody(update)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteReport(reportId: String): Result<Unit> {
        return try {
            var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/reports") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$reportId")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchReports(
        token: String,
        logLabel: String,
        reporterId: String?
    ): Result<List<Report>> {
        return try {
            android.util.Log.d(
                "MessageReportStore",
                "$logLabel: Fetching ${if (reporterId == null) "admin reports" else "current-user reports"}"
            )

            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/reports") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "*")
                parameter("order", "created_at.desc")
                reporterId?.let { parameter("reporter_id", "eq.$it") }
            }

            if (response.status.isSuccess()) {
                val rawJson = response.bodyAsText()

                try {
                    val reports = json.decodeFromString<List<Report>>(rawJson)
                    android.util.Log.d("MessageReportStore", "$logLabel: Successfully deserialized ${reports.size} reports")
                    Result.success(reports)
                } catch (e: Exception) {
                    android.util.Log.e("MessageReportStore", "DESERIALIZATION ERROR in $logLabel", e)
                    Result.failure(Exception("Error parsing data: ${e.message}"))
                }
            } else {
                val errorBody = response.bodyAsText()
                android.util.Log.e("MessageReportStore", "$logLabel error: ${response.status}, body: $errorBody")
                Result.failure(Exception("Failed to fetch reports: ${response.status}"))
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageReportStore", "$logLabel CRASHED during fetch", e)
            Result.failure(e)
        }
    }

    private fun isMissingRpcFunction(statusCode: Int, body: String, functionName: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val cleanBody = body.lowercase()
        return cleanBody.contains(functionName.lowercase()) &&
            (cleanBody.contains("could not find the function") || cleanBody.contains("undefined function"))
    }
}



