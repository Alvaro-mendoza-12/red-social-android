package conectate.com.core.security

import conectate.com.core.logging.AppLogger
import conectate.com.core.telemetry.AppTelemetry
import conectate.com.core.telemetry.TelemetryEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

enum class GuardedAction(
    val maxEvents: Int,
    val windowMs: Long,
    val minIntervalMs: Long
) {
    DIRECT_MESSAGE_SEND(maxEvents = 24, windowMs = 60_000L, minIntervalMs = 350L),
    GROUP_MESSAGE_SEND(maxEvents = 30, windowMs = 60_000L, minIntervalMs = 300L),
    COMMUNITY_MESSAGE_SEND(maxEvents = 36, windowMs = 60_000L, minIntervalMs = 250L),
    REPORT_SUBMISSION(maxEvents = 5, windowMs = 10 * 60_000L, minIntervalMs = 10_000L)
}

enum class GuardOutcome {
    BLOCKED,
    QUEUED,
    SUCCESS,
    FAILED
}

data class GuardDecision(
    val allowed: Boolean,
    val retryAfterMs: Long = 0L
)

object InputSecurityPolicy {
    const val MAX_TEXT_MESSAGE_LENGTH = 4000
    const val MAX_REPORT_REASON_LENGTH = 120
    const val MAX_REPORT_DESCRIPTION_LENGTH = 2000

    fun sanitizeMessageText(value: String): String {
        return value.trim().take(MAX_TEXT_MESSAGE_LENGTH)
    }

    fun sanitizeReason(value: String): String {
        return value.trim().take(MAX_REPORT_REASON_LENGTH)
    }

    fun sanitizeDescription(value: String): String {
        return value.trim().take(MAX_REPORT_DESCRIPTION_LENGTH)
    }

    fun retryAfterSeconds(retryAfterMs: Long): Long {
        return max(1L, (retryAfterMs + 999L) / 1000L)
    }
}

@Singleton
class OperationGuard @Inject constructor(
    private val telemetry: AppTelemetry,
    private val logger: AppLogger
) {
    private val buckets = ConcurrentHashMap<String, RateBucket>()
    private val acquisitionsSinceMaintenance = AtomicInteger(0)

    suspend fun acquire(
        action: GuardedAction,
        actorId: String,
        scopeId: String? = null,
        enforceMinInterval: Boolean = true
    ): GuardDecision {
        val nowMs = System.currentTimeMillis()
        val key = buildBucketKey(action, actorId, scopeId)
        maintainBuckets(nowMs, key)
        val bucket = buckets.computeIfAbsent(key) { RateBucket() }

        val decision = synchronized(bucket) {
            bucket.lastTouchedAtMs = nowMs
            evaluateBucket(bucket, action, nowMs, enforceMinInterval)
        }

        if (!decision.allowed) {
            audit(
                action = action,
                actorId = actorId,
                scopeId = scopeId,
                outcome = GuardOutcome.BLOCKED,
                attributes = mapOf("retry_after_ms" to decision.retryAfterMs.toString())
            )
        }

        return decision
    }

    private fun maintainBuckets(nowMs: Long, protectedKey: String) {
        val isPeriodicMaintenance =
            acquisitionsSinceMaintenance.incrementAndGet() % BUCKET_MAINTENANCE_INTERVAL == 0
        val needsCapacity = !buckets.containsKey(protectedKey) && buckets.size >= MAX_ACTIVE_BUCKETS
        if (!isPeriodicMaintenance && !needsCapacity) return

        buckets.entries.forEach { entry ->
            if (entry.key == protectedKey) return@forEach
            val idle = synchronized(entry.value) {
                nowMs - entry.value.lastTouchedAtMs >= BUCKET_IDLE_RETENTION_MS
            }
            if (idle) {
                buckets.remove(entry.key, entry.value)
            }
        }

        val targetSize = if (needsCapacity) MAX_ACTIVE_BUCKETS - 1 else MAX_ACTIVE_BUCKETS
        val excess = buckets.size - targetSize
        if (excess <= 0) return

        buckets.entries
            .asSequence()
            .filter { it.key != protectedKey }
            .map { entry ->
                entry to synchronized(entry.value) { entry.value.lastTouchedAtMs }
            }
            .sortedBy { it.second }
            .take(excess)
            .forEach { (entry, _) -> buckets.remove(entry.key, entry.value) }
    }

    internal fun activeBucketCountForTesting(): Int = buckets.size

    suspend fun audit(
        action: GuardedAction,
        actorId: String,
        scopeId: String? = null,
        outcome: GuardOutcome,
        attributes: Map<String, String> = emptyMap()
    ) {
        val payload = LinkedHashMap<String, String>()
        payload["action"] = action.name.lowercase()
        payload["outcome"] = outcome.name.lowercase()
        payload["actor_id"] = actorId.trim().lowercase()
        if (!scopeId.isNullOrBlank()) {
            payload["scope_id"] = scopeId.trim().lowercase()
        }
        attributes.forEach { (key, value) ->
            if (value.isNotBlank()) {
                payload[key] = value
            }
        }

        telemetry.track(
            TelemetryEvent(
                name = "security_audit",
                attributes = payload
            )
        )

        when (outcome) {
            GuardOutcome.BLOCKED,
            GuardOutcome.FAILED -> logger.w(
                TAG,
                "audit action=${payload["action"]} outcome=${payload["outcome"]} actor=${payload["actor_id"]}"
            )

            GuardOutcome.QUEUED,
            GuardOutcome.SUCCESS -> logger.i(
                TAG,
                "audit action=${payload["action"]} outcome=${payload["outcome"]} actor=${payload["actor_id"]}"
            )
        }
    }

    private fun evaluateBucket(
        bucket: RateBucket,
        action: GuardedAction,
        nowMs: Long,
        enforceMinInterval: Boolean
    ): GuardDecision {
        while (bucket.acceptedAtMs.isNotEmpty()) {
            var oldest = bucket.acceptedAtMs.first()
            if (nowMs - oldest >= action.windowMs) {
                bucket.acceptedAtMs.removeFirst()
            } else {
                break
            }
        }

        if (enforceMinInterval && action.minIntervalMs > 0 && bucket.lastAcceptedAtMs > 0) {
            val elapsed = nowMs - bucket.lastAcceptedAtMs
            if (elapsed < action.minIntervalMs) {
                return GuardDecision(
                    allowed = false,
                    retryAfterMs = action.minIntervalMs - elapsed
                )
            }
        }

        if (bucket.acceptedAtMs.size >= action.maxEvents) {
            var oldest = bucket.acceptedAtMs.first()
            val retryAfter = max(1L, action.windowMs - (nowMs - oldest))
            return GuardDecision(allowed = false, retryAfterMs = retryAfter)
        }

        bucket.acceptedAtMs.addLast(nowMs)
        bucket.lastAcceptedAtMs = nowMs
        return GuardDecision(allowed = true)
    }

    private fun buildBucketKey(
        action: GuardedAction,
        actorId: String,
        scopeId: String?
    ): String {
        val cleanActorId = actorId.trim().lowercase()
        val cleanScopeId = scopeId?.trim()?.lowercase()
        return if (cleanScopeId.isNullOrBlank()) {
            "${action.name}:$cleanActorId"
        } else {
            "${action.name}:$cleanActorId:$cleanScopeId"
        }
    }

    private data class RateBucket(
        val acceptedAtMs: ArrayDeque<Long> = ArrayDeque(),
        var lastAcceptedAtMs: Long = 0L,
        var lastTouchedAtMs: Long = 0L
    )

    companion object {
        private const val TAG = "OperationGuard"
        internal const val MAX_ACTIVE_BUCKETS = 2_048
        private const val BUCKET_MAINTENANCE_INTERVAL = 128
        private const val BUCKET_IDLE_RETENTION_MS = 15 * 60_000L
    }
}





