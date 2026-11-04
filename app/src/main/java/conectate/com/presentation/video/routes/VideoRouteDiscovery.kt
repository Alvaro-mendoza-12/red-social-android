package conectate.com.presentation.video.routes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private data class RouteTopic(val name: String, val hint: String, val icon: ImageVector, val tint: Color)

private val routeTopics = listOf(
    RouteTopic("Música", "Un nuevo ritmo", Icons.Default.MusicNote, RouteMint),
    RouteTopic("Cocina", "Algo para probar", Icons.Default.Restaurant, RoutePeach),
    RouteTopic("Juegos", "Tu próxima jugada", Icons.Default.SportsEsports, RouteSky),
    RouteTopic("Ciencia", "Una nueva pregunta", Icons.Default.Science, RouteLilac),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoRouteDiscovery(
    state: VideoRoutesState,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onSizeChange: (Int) -> Unit,
    onPrepare: () -> Unit,
    onShowRoute: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(28.dp), color = RouteIndigo, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tu próximo descubrimiento", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(Icons.Default.Explore, null, modifier = Modifier.size(24.dp), tint = RouteMint)
            }
            Text("Un tema.\nAlgo que te llevas.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            RouteTrailArtwork(List(state.requestedCount) { false }, Modifier.fillMaxWidth().height(45.dp), onColor = RouteMint, offColor = Color.White)
        }
    }
    OutlinedTextField(
        value = state.query, onValueChange = onQueryChange, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        label = { Text("¿Qué quieres explorar?") }, placeholder = { Text("Busca un tema") },
        leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (enabled && state.query.isNotBlank()) onPrepare() }),
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Ideas para empezar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Default.AutoAwesome, null, tint = routeAccent(), modifier = Modifier.size(20.dp))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 640.dp) 4 else 2
        val tileWidth = (maxWidth - 12.dp * (columns - 1)) / columns
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            routeTopics.forEach { topic ->
                val selected = state.query.trim().equals(topic.name, ignoreCase = true)
                Surface(
                    onClick = { onQueryChange(topic.name) }, enabled = enabled,
                    modifier = Modifier.width(tileWidth).semantics { this.selected = selected; role = Role.RadioButton }, shape = RoundedCornerShape(22.dp),
                    color = topic.tint, contentColor = RouteInk,
                    border = if (selected) BorderStroke(2.dp, RouteIndigo) else null,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Icon(topic.icon, null, Modifier.size(24.dp))
                            if (selected) Icon(Icons.Default.CheckCircle, "Tema elegido", Modifier.size(20.dp), tint = RouteIndigo)
                        }
                        Text(topic.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(topic.hint, style = MaterialTheme.typography.bodySmall, color = RouteInk.copy(alpha = .8f))
                    }
                }
            }
        }
    }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tu recorrido", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VideoRoute.SIZES.forEach { count ->
                    FilterChip(
                        selected = state.requestedCount == count, enabled = enabled, onClick = { onSizeChange(count) },
                        label = { Text("$count videos") },
                        leadingIcon = { Icon(if (count == 3) Icons.Default.Bolt else Icons.Default.Explore, null, Modifier.size(18.dp)) },
                    )
                }
            }
            Text("Una selección con final. Tú eliges lo que te sirve.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (state.route != null) {
        Text("Al preparar otra ruta se reemplazará la selección y su conclusión en este dispositivo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onShowRoute, enabled = !state.busy) { Text("Volver a mi ruta") }
    }
}
