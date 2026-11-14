package conectate.com.presentation.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun LiveChannelPointsDialog(
    visit: LiveToolAccountVisit,
    state: LiveChannelPointsUiState,
    isHost: Boolean,
    onDismiss: () -> Unit,
    onRedeem: (String) -> Unit,
    onUpdateSettings: (Boolean, Int, Int, Int) -> Unit,
    onCreateReward: (String, String, Int, Int?, Int) -> Unit,
    onUpdateRedemption: (String, String) -> Unit,
) {
    key(visit.key) {
        if (visit.isCurrent) LiveChannelPointsContent(
            state, isHost,
            onDismiss = { visit.run(onDismiss) },
            onRedeem = { visit.run { onRedeem(it) } },
            onUpdateSettings = { enabled, interval, points, cap -> visit.run { onUpdateSettings(enabled, interval, points, cap) } },
            onCreateReward = { title, description, cost, stock, cooldown -> visit.run { onCreateReward(title, description, cost, stock, cooldown) } },
            onUpdateRedemption = { id, status -> visit.run { onUpdateRedemption(id, status) } },
        )
    }
}

@Composable
private fun LiveChannelPointsContent(
    state: LiveChannelPointsUiState,
    isHost: Boolean,
    onDismiss: () -> Unit,
    onRedeem: (String) -> Unit,
    onUpdateSettings: (Boolean, Int, Int, Int) -> Unit,
    onCreateReward: (String, String, Int, Int?, Int) -> Unit,
    onUpdateRedemption: (String, String) -> Unit,
) {
    val settings = state.settings
    var enabled by remember { mutableStateOf(settings?.enabled ?: true) }
    var intervalMinutes by remember { mutableStateOf(((settings?.intervalSeconds ?: 300) / 60).toString()) }
    var pointsPerInterval by remember { mutableStateOf((settings?.pointsPerInterval ?: 10).toString()) }
    var dailyCap by remember { mutableStateOf((settings?.dailyCap ?: 2_000).toString()) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }
    var cooldownMinutes by remember { mutableStateOf("0") }
    var acknowledgedCreation by remember { mutableIntStateOf(state.rewardCreationVersion) }

    LaunchedEffect(state.rewardCreationVersion) {
        if (acknowledgedCreation != state.rewardCreationVersion) {
            title = ""; description = ""; cost = ""; stock = ""; cooldownMinutes = "0"
            acknowledgedCreation = state.rewardCreationVersion
        }
    }

    LaunchedEffect(settings) {
        settings ?: return@LaunchedEffect
        enabled = settings.enabled
        intervalMinutes = (settings.intervalSeconds / 60).toString()
        pointsPerInterval = settings.pointsPerInterval.toString()
        dailyCap = settings.dailyCap.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isHost) "Puntos del canal" else "Recompensas del canal") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("${state.balance} puntos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                            Text("Son gratuitos, pertenecen a este canal y no se compran ni se retiran.")
                            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }

                if (isHost) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Permitir puntos", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                    }
                    item { NumberField("Minutos por premio", intervalMinutes) { intervalMinutes = it } }
                    item { NumberField("Puntos por intervalo", pointsPerInterval) { pointsPerInterval = it } }
                    item { NumberField("Límite diario", dailyCap) { dailyCap = it } }
                    item {
                        Button(
                            onClick = {
                                onUpdateSettings(
                                    enabled,
                                    (intervalMinutes.toIntOrNull() ?: 5).coerceIn(1, 60) * 60,
                                    (pointsPerInterval.toIntOrNull() ?: 10).coerceIn(1, 1_000),
                                    (dailyCap.toIntOrNull() ?: 2_000).coerceIn(1, 100_000),
                                )
                            },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Guardar reglas") }
                    }
                    item { HorizontalDivider() }
                    item { Text("Nueva recompensa", fontWeight = FontWeight.Black) }
                    item { OutlinedTextField(title, { title = it.take(80) }, enabled = !state.busy, label = { Text("Título") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(description, { description = it.take(240) }, enabled = !state.busy, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth()) }
                    item { NumberField("Costo en puntos", cost, enabled = !state.busy) { cost = it } }
                    item { NumberField("Cupos (vacío = ilimitado)", stock, enabled = !state.busy) { stock = it } }
                    item { NumberField("Espera entre canjes (min)", cooldownMinutes, enabled = !state.busy) { cooldownMinutes = it } }
                    item {
                        Button(
                            onClick = {
                                val parsedCost = cost.toIntOrNull() ?: return@Button
                                onCreateReward(
                                    title,
                                    description,
                                    parsedCost.coerceIn(1, 1_000_000),
                                    stock.toIntOrNull()?.coerceIn(0, 1_000_000),
                                    (cooldownMinutes.toIntOrNull() ?: 0).coerceIn(0, 1_440) * 60,
                                )
                            },
                            enabled = title.isNotBlank() && (cost.toIntOrNull() ?: 0) > 0 && !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Crear recompensa") }
                    }
                }

                item { HorizontalDivider() }
                item { Text("Recompensas", fontWeight = FontWeight.Black) }
                if (state.rewards.isEmpty()) item { Text("El creador todavía no publicó recompensas.") }
                items(state.rewards, key = { it.id }) { reward ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(reward.title, fontWeight = FontWeight.Bold)
                                if (reward.description.isNotBlank()) Text(reward.description, style = MaterialTheme.typography.bodySmall)
                                Text("${reward.cost} puntos${reward.stock?.let { " · $it disponibles" }.orEmpty()}")
                            }
                            if (!isHost) {
                                TextButton(
                                    onClick = { onRedeem(reward.id) },
                                    enabled = reward.active && (reward.stock == null || reward.stock > 0) && state.balance >= reward.cost && !state.busy,
                                ) { Text("Canjear") }
                            }
                        }
                    }
                }

                if (isHost) {
                    item { HorizontalDivider() }
                    item { Text("Solicitudes", fontWeight = FontWeight.Black) }
                    if (state.redemptions.isEmpty()) item { Text("No hay solicitudes de canje.") }
                    items(state.redemptions, key = { it.id }) { redemption ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(redemption.rewardTitle ?: "Recompensa", fontWeight = FontWeight.Bold)
                            Text("${redemption.viewerId.take(18)} · ${redemption.cost} puntos · ${redemption.status}")
                            when (redemption.status) {
                                "requested" -> Row {
                                    TextButton(onClick = { onUpdateRedemption(redemption.id, "approved") }) { Text("Aprobar") }
                                    TextButton(onClick = { onUpdateRedemption(redemption.id, "rejected") }) { Text("Rechazar") }
                                }
                                "approved" -> Row {
                                    TextButton(onClick = { onUpdateRedemption(redemption.id, "completed") }) { Text("Completar") }
                                    TextButton(onClick = { onUpdateRedemption(redemption.id, "canceled") }) { Text("Cancelar") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun NumberField(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        enabled = enabled,
        onValueChange = { text -> onChange(text.filter(Char::isDigit).take(7)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
