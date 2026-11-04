package conectate.com.presentation.video.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoRouteCollection(
    route: VideoRoute,
    conclusion: String,
    unsavedConclusion: Boolean,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onUseful: (String) -> Unit,
    onEditConclusion: () -> Unit,
    onCompleted: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(RouteLilac, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (route.completed) Icons.Default.Check else Icons.Default.Route, null, tint = RouteIndigo)
                }
                Column(Modifier.weight(1f)) {
                    Text(if (route.completed) "Ruta terminada" else "Tu ruta", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(route.topic, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "${route.items.size} ${if (route.items.size == 1) "video" else "videos"} de ${route.authorCount} " +
                    if (route.authorCount == 1) "creador" else "creadores",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RouteTrailArtwork(route.items.map(VideoRouteItem::opened), Modifier.fillMaxWidth().height(32.dp), onColor = routeAccent())
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoRouteMetrics(route)
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    VideoRouteMetrics(route)
                }
            }
            val next = route.items.firstOrNull { !it.opened }
            if (!route.completed && next != null) Button(
                onClick = { onOpen(next.videoId) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RouteIndigo, contentColor = Color.White),
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (route.openedCount == 0) "Empezar ruta" else "Continuar ruta")
            }
        }
    }
    if (route.items.size < route.requestedCount) Text(
        "Encontramos ${route.items.size} de los ${route.requestedCount} que pediste. Puedes recorrer esta selección o probar otro tema.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Tu selección", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimumCard = 300.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = if (maxWidth >= minimumCard * 2 + 12.dp) 2 else 1
        val cardWidth = (maxWidth - 12.dp * (columns - 1)) / columns
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            route.items.forEachIndexed { index, item ->
                VideoRouteEntryCard(item, index, enabled, onOpen, onUseful, Modifier.width(cardWidth))
            }
        }
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Bookmarks, null, tint = routeAccent(), modifier = Modifier.size(22.dp))
                Text("Lo que me llevo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Text(
                conclusion.ifBlank { "Quédate con una idea para después." }, maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Keep the complete action on its own line when large type needs the width.
            Text(if (unsavedConclusion) "Cambios sin guardar" else "Solo para ti", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onEditConclusion, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (conclusion.isBlank()) "Escribir" else "Editar")
            }
        }
    }
    OutlinedButton(
        onClick = { onCompleted(!route.completed) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(if (route.completed) "Reabrir ruta" else "Terminar ruta") }
}

@Composable
private fun VideoRouteMetrics(route: VideoRoute) {
    Text("${route.openedCount} de ${route.items.size} abiertos", style = MaterialTheme.typography.labelMedium)
    Text("${route.usefulCount} ${if (route.usefulCount == 1) "útil" else "útiles"} para ti", style = MaterialTheme.typography.labelMedium, color = routeAccent())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoRouteEntryCard(
    item: VideoRouteItem,
    index: Int,
    enabled: Boolean,
    onOpen: (String) -> Unit,
    onUseful: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val actionsBelow = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    VideoRouteNumberedPoster(item, index, enabled, onOpen)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(item.authorName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!actionsBelow) VideoRouteEntryActions(item, enabled, onOpen, onUseful)
                    }
                }
                if (actionsBelow) VideoRouteEntryActions(item, enabled, onOpen, onUseful)
            }
        }
    }
}

@Composable
private fun VideoRouteNumberedPoster(item: VideoRouteItem, index: Int, enabled: Boolean, onOpen: (String) -> Unit) {
    Box(Modifier.width(78.dp).height(120.dp).clip(RoundedCornerShape(18.dp))
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Abrir video") { onOpen(item.videoId) }
        .semantics { contentDescription = "Abrir video ${item.title}" }) {
        VideoRoutePoster(item, Modifier.fillMaxSize())
        Surface(Modifier.align(Alignment.TopStart).padding(5.dp), shape = CircleShape, color = RouteInk, contentColor = Color.White) {
            Text("${index + 1}", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
        }
        if (item.durationSeconds > 0) Surface(
            Modifier.align(Alignment.BottomEnd).padding(5.dp), shape = RoundedCornerShape(6.dp), color = RouteInk, contentColor = Color.White,
        ) {
            Text("${item.durationSeconds / 60}:${(item.durationSeconds % 60).toString().padStart(2, '0')}", Modifier.padding(4.dp, 2.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoRouteEntryActions(item: VideoRouteItem, enabled: Boolean, onOpen: (String) -> Unit, onUseful: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(
            onClick = { onOpen(item.videoId) }, enabled = enabled, contentPadding = PaddingValues(horizontal = 2.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = routeAccent()),
        ) {
            Text(if (item.opened) "Volver a abrir" else "Abrir video")
        }
        FilterChip(
            selected = item.useful, onClick = { onUseful(item.videoId) }, enabled = enabled && item.opened,
            label = { Text("Me sirvió") },
            leadingIcon = if (item.useful) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null,
        )
    }
}
