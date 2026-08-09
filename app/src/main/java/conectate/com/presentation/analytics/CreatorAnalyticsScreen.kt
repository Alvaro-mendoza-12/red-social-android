package conectate.com.presentation.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.data.model.CreatorAnalyticsContent
import conectate.com.data.model.CreatorAnalyticsPrivacy
import conectate.com.data.model.LiveAnalyticsReport
import conectate.com.data.model.VideoAnalyticsReport
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorAnalyticsScreen(
    navController: NavController,
    viewModel: CreatorAnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var contentType by remember { mutableStateOf("video") }
    LaunchedEffect(state.items) {
        if (state.items.none { it.contentType == contentType }) {
            contentType = state.items.firstOrNull()?.contentType ?: "video"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Estadísticas del creador") },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))
            state.items.isEmpty() && state.error != null -> ErrorState(
                message = state.error.orEmpty(),
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(padding),
            )
            state.items.isEmpty() -> EmptyState(Modifier.padding(padding))
            else -> {
                val filtered = state.items.filter { it.contentType == contentType }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        PrivacyIntro(Modifier.padding(horizontal = 16.dp))
                    }
                    item {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.items.any(CreatorAnalyticsContent::isVideo)) {
                                FilterChip(
                                    selected = contentType == "video",
                                    onClick = { contentType = "video" },
                                    label = { Text("Videos") },
                                    leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
                                )
                            }
                            if (state.items.any(CreatorAnalyticsContent::isLive)) {
                                FilterChip(
                                    selected = contentType == "live",
                                    onClick = { contentType = "live" },
                                    label = { Text("Directos") },
                                    leadingIcon = { Icon(Icons.Default.LiveTv, contentDescription = null) },
                                )
                            }
                        }
                    }
                    item {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        ) {
                            items(filtered, key = { "${it.contentType}:${it.contentId}" }) { content ->
                                ContentCard(
                                    content = content,
                                    selected = state.selected?.contentId == content.contentId,
                                    onClick = { viewModel.select(content) },
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = if (contentType == "video") {
                                    "Todavía no has publicado videos."
                                } else {
                                    "Todavía no has creado directos."
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.isReportLoading) {
                        item { LoadingReport() }
                    } else if (state.error != null) {
                        item {
                            ErrorCard(
                                message = state.error.orEmpty(),
                                onRetry = { state.selected?.let(viewModel::select) },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    } else {
                        state.videoReport?.let { report ->
                            item { VideoReport(report, Modifier.padding(horizontal = 16.dp)) }
                        }
                        state.liveReport?.let { report ->
                            item { LiveReport(report, Modifier.padding(horizontal = 16.dp)) }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Reintentar") }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Analytics, contentDescription = null)
        Spacer(Modifier.height(12.dp))
        Text("Aún no hay contenido para analizar", style = MaterialTheme.typography.titleMedium)
        Text(
            "Cuando publiques un video o finalices un directo aparecerá aquí.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrivacyIntro(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Info, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Datos privados y protegidos", fontWeight = FontWeight.SemiBold)
                Text(
                    "Solo tú puedes consultar estos informes. Las regiones, retención y curvas se ocultan cuando el grupo es demasiado pequeño.",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ContentCard(
    content: CreatorAnalyticsContent,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.width(220.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (content.isVideo) Icons.Default.Movie else Icons.Default.LiveTv,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    content.title.ifBlank { if (content.isVideo) "Video sin título" else "Directo sin título" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(content.statusLabel(), style = MaterialTheme.typography.labelMedium)
            content.occurredAt?.let {
                Text(formatDate(it), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LoadingReport() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.width(24.dp))
        Spacer(Modifier.width(12.dp))
        Text("Calculando el informe…")
    }
}

@Composable
private fun VideoReport(report: VideoAnalyticsReport, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportHeading("Rendimiento del video", report.range.from, report.range.to)
        if (!report.hasConfirmedActivity()) NoActivityCard("Todavía no hay actividad confirmada para este video.")
        MetricGrid(
            listOf(
                "Reproducciones" to formatNumber(report.totals.views),
                "Espectadores únicos" to formatOptional(report.totals.uniqueViewers),
                "Minutos vistos" to formatOptional(report.totals.watchMinutes),
                "Promedio visto" to report.totals.averageWatchSeconds?.let { "${formatDecimal(it)} s" }.orProtected(),
                "Me gusta" to formatNumber(report.totals.interactions.likes),
                "Comentarios" to formatNumber(report.totals.interactions.comments),
                "Compartidos" to formatNumber(report.totals.interactions.shares),
                "Nuevos seguidores" to formatNumber(report.totals.interactions.follows),
            ),
        )
        PrivacyCard(report.privacy)
        if (report.privacy.detailedMetricsAvailable) {
            RetentionCard(report)
            if (report.countries.isNotEmpty()) {
                SimpleSection("Países con mayor audiencia") {
                    report.countries.take(10).forEach { country ->
                        KeyValueRow(country.countryCode, "${formatNumber(country.views)} vistas")
                    }
                }
            }
            if (report.timeline.isNotEmpty()) {
                SimpleSection("Actividad reciente") {
                    report.timeline.takeLast(8).forEach { point ->
                        KeyValueRow(formatDate(point.bucket), "${formatNumber(point.views)} vistas")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveReport(report: LiveAnalyticsReport, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportHeading("Rendimiento del directo", report.range.from, report.range.to)
        if (!report.hasConfirmedActivity()) NoActivityCard("Todavía no hay actividad confirmada para este directo.")
        MetricGrid(
            listOf(
                "Pico simultáneo" to formatNumber(report.totals.peakConcurrent),
                "Espectadores únicos" to formatOptional(report.totals.uniqueViewers),
                "Promedio simultáneo" to formatOptional(report.totals.averageConcurrent),
                "Minutos vistos" to formatOptional(report.totals.viewerMinutes),
                "Me gusta" to formatNumber(report.totals.likes),
                "Comentarios" to formatNumber(report.totals.comments),
                "Regalos confirmados" to formatNumber(report.totals.gifts),
                "Monedas regaladas" to formatNumber(report.totals.giftValueCoins),
            ),
        )
        PrivacyCard(report.privacy)
        if (report.privacy.detailedMetricsAvailable && report.viewerCurve.isNotEmpty()) {
            SimpleSection("Audiencia por minuto") {
                report.viewerCurve.takeLast(12).forEach { point ->
                    KeyValueRow(formatDate(point.bucket), formatNumber(point.concurrent))
                }
            }
        }
        if (report.privacy.topDonorsAvailable && report.topDonors.isNotEmpty()) {
            SimpleSection("Apoyo del directo") {
                Text(
                    "El informe conserva el anonimato de los donantes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                report.topDonors.forEach { donor ->
                    KeyValueRow("Puesto ${donor.rank}", "${formatNumber(donor.valueCoins)} monedas")
                }
            }
        }
    }
}

@Composable
private fun ReportHeading(title: String, from: String, to: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${formatDate(from)} – ${formatDate(to)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricGrid(metrics: List<Pair<String, String>>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 700.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.chunked(columns).forEach { rowMetrics ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowMetrics.forEach { (label, value) ->
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    repeat(columns - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PrivacyCard(privacy: CreatorAnalyticsPrivacy) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("Umbral de privacidad", fontWeight = FontWeight.SemiBold)
            Text(
                if (privacy.detailedMetricsAvailable) {
                    "Se alcanzó el mínimo de ${privacy.minimumCohort} personas para mostrar métricas agrupadas."
                } else {
                    "Se necesitan al menos ${privacy.minimumCohort} personas. Los detalles aparecen como Protegido hasta alcanzar ese mínimo."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Novvin no entrega identificadores individuales en este informe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun RetentionCard(report: VideoAnalyticsReport) {
    SimpleSection("Retención") {
        KeyValueRow("25 % del video", formatPercent(report.totals.retention.percent25))
        KeyValueRow("50 % del video", formatPercent(report.totals.retention.percent50))
        KeyValueRow("75 % del video", formatPercent(report.totals.retention.percent75))
        KeyValueRow("Video completo", formatPercent(report.totals.retention.percent100))
    }
}

@Composable
private fun NoActivityCard(message: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Text(message, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry) { Text("Reintentar") }
        }
    }
}

@Composable
private fun SimpleSection(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
    Divider()
}

private fun CreatorAnalyticsContent.statusLabel(): String = when (status) {
    "published" -> "Publicado"
    "private" -> "Privado"
    "live" -> "En vivo"
    "ended" -> "Finalizado"
    "draft" -> "Borrador"
    else -> status.ifBlank { "Contenido" }
}

private fun formatNumber(value: Number): String = NumberFormat.getIntegerInstance().format(value.toLong())
private fun formatDecimal(value: Double): String = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = 1
}.format(value)
private fun formatOptional(value: Number?): String = value?.let {
    if (it is Double || it is Float) formatDecimal(it.toDouble()) else formatNumber(it)
}.orProtected()
private fun formatPercent(value: Double?): String = value?.let { "${formatDecimal(it)} %" }.orProtected()
private fun String?.orProtected(): String = this ?: "Protegido"

private fun formatDate(value: String): String = runCatching {
    val instant = Instant.parse(value)
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instant)
}.getOrElse { value }
