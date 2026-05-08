package conectate.com.analytics.enterprise

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Módulo predictivo en modo seguro.
 *
 * La implementación previa intentaba levantar un stack ML completo dentro del APK,
 * pero no estaba integrada de forma real con el producto y rompía el build.
 * Dejamos una versión compilable y degradada que conserva la API base mientras
 * se diseña una implementación de producción con modelos/versionado/backend reales.
 */
class PredictiveAnalyticsManager private constructor(
    private val context: Context
) {
    companion object {
        @Volatile
        private var INSTANCE: PredictiveAnalyticsManager? = null

        fun getInstance(context: Context): PredictiveAnalyticsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PredictiveAnalyticsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val _analyticsState = MutableStateFlow<AnalyticsState>(AnalyticsState.Ready)
    val analyticsState: StateFlow<AnalyticsState> = _analyticsState.asStateFlow()

    private val _predictionEvents = MutableSharedFlow<PredictionEvent>(extraBufferCapacity = 32)
    val predictionEvents = _predictionEvents.asSharedFlow()

    private val behaviorEvents = ConcurrentHashMap<String, MutableList<UserBehaviorEvent>>()
    private val contentInteractions = ConcurrentHashMap<String, MutableList<ContentInteraction>>()
    private val sessionEvents = ConcurrentHashMap<String, MutableList<SessionEvent>>()

    suspend fun predictUserBehavior(userId: String): UserBehaviorPrediction {
        val events = behaviorEvents[userId].orEmpty()
        val confidence = if (events.isEmpty()) 0.15f else 0.55f
        val result = UserBehaviorPrediction.Success(
            userId = userId,
            behaviorCategory = if (events.size > 20) BehaviorCategory.HIGHLY_ENGAGED else BehaviorCategory.CASUAL_USER,
            confidence = confidence,
            predictions = listOf(confidence, 0.2f, 0.1f, 0.1f, 0.05f),
            timestamp = System.currentTimeMillis()
        )
        _predictionEvents.tryEmit(PredictionEvent.UserBehaviorPredicted(result))
        return result
    }

    suspend fun recommendContent(userId: String, limit: Int = 10): ContentRecommendation {
        val interactions = contentInteractions[userId].orEmpty()
        val items = interactions
            .sortedByDescending { it.timestamp }
            .map { it.contentId }
            .distinct()
            .take(limit)
        return ContentRecommendation.Success(
            userId = userId,
            recommendedContentIds = items,
            confidence = if (items.isEmpty()) 0.1f else 0.45f,
            timestamp = System.currentTimeMillis()
        )
    }

    suspend fun predictChurn(userId: String): ChurnPrediction {
        val sessions = sessionEvents[userId].orEmpty()
        val risk = if (sessions.isEmpty()) 0.6f else 0.25f
        return ChurnPrediction.Success(
            userId = userId,
            churnRisk = risk,
            riskLevel = when {
                risk >= 0.7f -> RiskLevel.HIGH
                risk >= 0.4f -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            },
            timestamp = System.currentTimeMillis()
        )
    }

    suspend fun calculateEngagementScore(userId: String): EngagementScore {
        val events = behaviorEvents[userId].orEmpty()
        val score = (events.size / 100f).coerceIn(0f, 1f)
        return EngagementScore.Success(
            userId = userId,
            score = score,
            label = when {
                score >= 0.7f -> "alto"
                score >= 0.4f -> "medio"
                else -> "bajo"
            },
            timestamp = System.currentTimeMillis()
        )
    }

    suspend fun detectAnomalies(userId: String): AnomalyDetection {
        return AnomalyDetection.Success(
            userId = userId,
            anomalies = emptyList(),
            timestamp = System.currentTimeMillis()
        )
    }

    fun recordUserBehaviorEvent(event: UserBehaviorEvent) {
        behaviorEvents.getOrPut(event.userId) { mutableListOf() }.add(event)
    }

    fun recordContentInteraction(interaction: ContentInteraction) {
        contentInteractions.getOrPut(interaction.userId) { mutableListOf() }.add(interaction)
    }

    fun recordSessionEvent(event: SessionEvent) {
        sessionEvents.getOrPut(event.userId) { mutableListOf() }.add(event)
    }
}

sealed class AnalyticsState {
    data object Idle : AnalyticsState()
    data object Ready : AnalyticsState()
    data class Error(val message: String) : AnalyticsState()
}

data class UserBehaviorEvent(
    val userId: String,
    val sessionId: String,
    val contentId: String = "",
    val type: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ContentInteraction(
    val userId: String,
    val contentId: String,
    val interactionType: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class SessionEvent(
    val userId: String,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class UserBehaviorPrediction {
    data class Success(
        val userId: String,
        val behaviorCategory: BehaviorCategory,
        val confidence: Float,
        val predictions: List<Float>,
        val timestamp: Long
    ) : UserBehaviorPrediction()

    data class Error(val message: String) : UserBehaviorPrediction()
}

enum class BehaviorCategory(val displayName: String) {
    NEW_USER("Usuario nuevo"),
    CASUAL_USER("Usuario casual"),
    HIGHLY_ENGAGED("Muy comprometido"),
    CREATOR("Creador"),
    AT_RISK("En riesgo");

    companion object {
        fun fromIndex(index: Int): BehaviorCategory = entries.getOrElse(index) { CASUAL_USER }
    }
}

sealed class ContentRecommendation {
    data class Success(
        val userId: String,
        val recommendedContentIds: List<String>,
        val confidence: Float,
        val timestamp: Long
    ) : ContentRecommendation()

    data class Error(val message: String) : ContentRecommendation()
}

sealed class ChurnPrediction {
    data class Success(
        val userId: String,
        val churnRisk: Float,
        val riskLevel: RiskLevel,
        val timestamp: Long
    ) : ChurnPrediction()

    data class Error(val message: String) : ChurnPrediction()
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

sealed class EngagementScore {
    data class Success(
        val userId: String,
        val score: Float,
        val label: String,
        val timestamp: Long
    ) : EngagementScore()

    data class Error(val message: String) : EngagementScore()
}

sealed class AnomalyDetection {
    data class Success(
        val userId: String,
        val anomalies: List<String>,
        val timestamp: Long
    ) : AnomalyDetection()

    data class Error(val message: String) : AnomalyDetection()
}

sealed class PredictionEvent {
    data class ModelLoaded(val modelName: String) : PredictionEvent()
    data class Error(val message: String) : PredictionEvent()
    data class UserBehaviorPredicted(val prediction: UserBehaviorPrediction.Success) : PredictionEvent()
}

@Composable
fun rememberPredictiveAnalytics(context: Context = LocalContext.current): PredictiveAnalyticsManager {
    return remember(context.applicationContext) {
        PredictiveAnalyticsManager.getInstance(context.applicationContext)
    }
}

@Composable
fun PredictiveAnalyticsDashboard(
    analyticsManager: PredictiveAnalyticsManager
) {
    val state = analyticsManager.analyticsState.collectAsState()
    Text(
        text = when (state.value) {
            AnalyticsState.Idle -> "Analítica preparándose"
            AnalyticsState.Ready -> "Analítica predictiva en modo seguro"
            is AnalyticsState.Error -> "Analítica no disponible"
        },
        style = MaterialTheme.typography.bodyMedium
    )
}
