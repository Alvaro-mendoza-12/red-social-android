@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package conectate.com.presentation.algorithm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Public topic context that a caller may show in recommendation feedback.
 *
 * `key` must be a public topic/subtopic token, never a profile, account, device
 * or content identifier. The sheet returns only the selected public topic key;
 * it deliberately has no access to the recommendation, account or creator.
 */
data class RecommendationFeedbackTopic(
    val key: String,
    val label: String,
)

/**
 * The local, declarative feedback choices a surface can offer for a recommendation.
 *
 * This is an intent only. It never changes preferences, hides content, calls a
 * repository or queues an offline mutation by itself. The caller must map the
 * choice to an authenticated, revision-controlled backend operation after any
 * required confirmation.
 */
enum class RecommendationFeedbackChoice(
    val label: String,
    val supportingText: String,
    internal val requiresTopic: Boolean = false,
) {
    ONLY_THIS_PUBLICATION(
        label = "Solo esta publicación",
        supportingText = "Oculta esta publicación sin cambiar tus demás preferencias.",
    ),
    TOPIC(
        label = "Este tema",
        supportingText = "Ajusta cuánto contenido de este tema quieres ver.",
        requiresTopic = true,
    ),
    SUBTOPIC(
        label = "Este subtema",
        supportingText = "Ajusta una parte más específica del tema elegido.",
        requiresTopic = true,
    ),
    CREATOR(
        label = "Este creador",
        supportingText = "Ajusta contenido de este creador sin compartir su identidad desde esta hoja.",
    ),
    CONTENT_TYPE(
        label = "Este tipo de contenido",
        supportingText = "Ajusta el formato, por ejemplo video, imagen o texto.",
    ),
    AUDIO_OR_TREND(
        label = "Este audio o tendencia",
        supportingText = "Ajusta contenido relacionado con este audio o tendencia.",
    ),
    SHOW_LESS_SIMILAR(
        label = "Mostrar menos parecido",
        supportingText = "Reduce recomendaciones similares sin bloquearlas por completo.",
    ),
    HIDE_TOPIC(
        label = "Ocultar tema",
        supportingText = "Deja de mostrar el tema elegido hasta que lo restaures en ajustes.",
        requiresTopic = true,
    ),
    MUTE_TEMPORARILY(
        label = "Silenciar temporalmente",
        supportingText = "Pausa el tema elegido por un periodo que confirmarás después.",
        requiresTopic = true,
    ),
}

/**
 * A privacy-minimal selection emitted by [RecommendationFeedbackSheet].
 *
 * It intentionally excludes recommendation IDs, creator IDs, user names,
 * activity history and all other personal data. The owning screen can resolve
 * the current public recommendation context only after an explicit backend call.
 */
data class RecommendationFeedbackSelection(
    val choice: RecommendationFeedbackChoice,
    val topicKey: String? = null,
)

/**
 * Two-step, reusable recommendation-feedback bottom sheet.
 *
 * The first layer gives the user context and lets them choose one of up to three
 * public topics. The second layer asks exactly what they want to change. Tapping
 * an option only calls [onSelection]; persistence and network work intentionally
 * remain outside this composable.
 */
@Composable
fun RecommendationFeedbackSheet(
    recommendationTitle: String,
    primaryTopics: List<RecommendationFeedbackTopic>,
    onDismissRequest: () -> Unit,
    onSelection: (RecommendationFeedbackSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topics = remember(primaryTopics) { normalizeRecommendationFeedbackTopics(primaryTopics) }
    var layer by rememberSaveable { mutableStateOf(RecommendationFeedbackLayer.OVERVIEW) }
    var selectedTopicKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTopic = topics.firstOrNull { it.key == selectedTopicKey } ?: topics.firstOrNull()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        windowInsets = WindowInsets.ime,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (layer) {
                    RecommendationFeedbackLayer.OVERVIEW -> RecommendationFeedbackOverview(
                        recommendationTitle = recommendationTitle,
                        topics = topics,
                        selectedTopicKey = selectedTopic?.key,
                        onSelectTopic = { selectedTopicKey = it },
                        onContinue = { layer = RecommendationFeedbackLayer.CHOICES },
                        onDismissRequest = onDismissRequest,
                    )

                    RecommendationFeedbackLayer.CHOICES -> RecommendationFeedbackChoices(
                        selectedTopic = selectedTopic,
                        onBack = { layer = RecommendationFeedbackLayer.OVERVIEW },
                        onChoice = { choice ->
                            onSelection(
                                RecommendationFeedbackSelection(
                                    choice = choice,
                                    topicKey = selectedTopic?.key.takeIf { choice.requiresTopic },
                                ),
                            )
                            onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.RecommendationFeedbackOverview(
    recommendationTitle: String,
    topics: List<RecommendationFeedbackTopic>,
    selectedTopicKey: String?,
    onSelectTopic: (String) -> Unit,
    onContinue: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    Text(
        text = "Personaliza esta recomendación",
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = recommendationTitle.trim().ifBlank { "Esta recomendación" },
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
    )
    Text(
        text = "Elige qué quieres ajustar. Este panel no cambia nada hasta que confirmes una opción.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (topics.isNotEmpty()) {
        Text(
            text = "Temas principales",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        LazyColumn(
            modifier = Modifier.height(112.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(topics, key = { it.key }) { topic ->
                FilterChip(
                    selected = topic.key == selectedTopicKey,
                    onClick = { onSelectTopic(topic.key) },
                    label = { Text(topic.label, maxLines = 1) },
                )
            }
        }
    } else {
        Text(
            text = "No hay temas públicos disponibles para esta recomendación. Aun así puedes ajustar la publicación, el creador o el formato.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.weight(1f))
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Cambiar esta recomendación")
    }
    TextButton(
        onClick = onDismissRequest,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text("Cancelar")
    }
}

@Composable
private fun ColumnScope.RecommendationFeedbackChoices(
    selectedTopic: RecommendationFeedbackTopic?,
    onBack: () -> Unit,
    onChoice: (RecommendationFeedbackChoice) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "¿Qué quieres cambiar de esta recomendación?",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            selectedTopic?.let { topic ->
                Text(
                    text = "Tema elegido: ${topic.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Text(
        text = "Las opciones por tema necesitan un tema público elegido. Las demás se pueden usar siempre.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(RecommendationFeedbackChoice.entries, key = { it.name }) { choice ->
            val enabled = !choice.requiresTopic || selectedTopic != null
            OutlinedButton(
                onClick = { onChoice(choice) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(choice.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = choice.supportingText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private enum class RecommendationFeedbackLayer {
    OVERVIEW,
    CHOICES,
}

/**
 * Normalizes only public topic tokens for UI use. Keeping this function pure also
 * makes it safe to reuse in a unit test without a Compose runtime.
 */
internal fun normalizeRecommendationFeedbackTopics(
    topics: List<RecommendationFeedbackTopic>,
): List<RecommendationFeedbackTopic> = topics
    .asSequence()
    .map { topic ->
        RecommendationFeedbackTopic(
            key = topic.key.trim().take(80),
            label = topic.label.trim().take(80),
        )
    }
    .filter { topic -> topic.key.isNotBlank() && topic.label.isNotBlank() }
    .distinctBy { topic -> topic.key.lowercase(Locale.ROOT) }
    .take(3)
    .toList()
