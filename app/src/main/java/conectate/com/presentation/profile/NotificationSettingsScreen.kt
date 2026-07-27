package conectate.com.presentation.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.data.localfallback.NotificationDigestPreferences
import java.util.TimeZone

private val weekdayNames = listOf(
    "Domingo",
    "Lunes",
    "Martes",
    "Miércoles",
    "Jueves",
    "Viernes",
    "Sábado"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    navController: NavController,
    viewModel: NotificationViewModel = hiltViewModel()
) {
    val state by viewModel.digestState.collectAsStateWithLifecycle()
    val saving by viewModel.digestSaving.collectAsStateWithLifecycle()
    val message by viewModel.digestMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var initializedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var enabled by rememberSaveable { mutableStateOf(false) }
    var frequency by rememberSaveable { mutableStateOf("daily") }
    var deliveryHour by rememberSaveable { mutableIntStateOf(18) }
    var deliveryWeekday by rememberSaveable { mutableIntStateOf(1) }
    var includeMentions by rememberSaveable { mutableStateOf(true) }
    var includeDirectMessages by rememberSaveable { mutableStateOf(false) }
    var includeCommunityActivity by rememberSaveable { mutableStateOf(true) }
    var includeUpcomingLives by rememberSaveable { mutableStateOf(true) }
    val timezoneOffsetMinutes = remember {
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
    }

    LaunchedEffect(Unit) {
        viewModel.loadNotificationDigest()
    }
    LaunchedEffect(state) {
        val preferences = (state as? NotificationDigestUiState.Ready)?.overview?.preferences
            ?: return@LaunchedEffect
        val key = preferences.updatedAt ?: preferences.lastDeliveredAt ?: "defaults"
        if (initializedKey == key) return@LaunchedEffect
        initializedKey = key
        enabled = preferences.enabled
        frequency = preferences.frequency
        deliveryHour = preferences.deliveryHour
        deliveryWeekday = preferences.deliveryWeekday
        includeMentions = preferences.includeMentions
        includeDirectMessages = preferences.includeDirectMessages
        includeCommunityActivity = preferences.includeCommunityActivity
        includeUpcomingLives = preferences.includeUpcomingLives
    }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearDigestMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Resumen de notificaciones") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        when (val current = state) {
            NotificationDigestUiState.Loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Cargando tus preferencias…")
                }
            }
            is NotificationDigestUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::loadNotificationDigest) {
                        Text("Reintentar")
                    }
                }
            }
            is NotificationDigestUiState.Ready -> {
                NotificationDigestSettingsContent(
                    modifier = Modifier.padding(padding),
                    enabled = enabled,
                    onEnabledChange = { enabled = it },
                    frequency = frequency,
                    onFrequencyChange = { frequency = it },
                    deliveryHour = deliveryHour,
                    onDeliveryHourChange = { deliveryHour = it },
                    deliveryWeekday = deliveryWeekday,
                    onDeliveryWeekdayChange = { deliveryWeekday = it },
                    includeMentions = includeMentions,
                    onIncludeMentionsChange = { includeMentions = it },
                    includeDirectMessages = includeDirectMessages,
                    onIncludeDirectMessagesChange = { includeDirectMessages = it },
                    includeCommunityActivity = includeCommunityActivity,
                    onIncludeCommunityActivityChange = { includeCommunityActivity = it },
                    includeUpcomingLives = includeUpcomingLives,
                    onIncludeUpcomingLivesChange = { includeUpcomingLives = it },
                    saving = saving,
                    onSave = {
                        viewModel.saveNotificationDigestPreferences(
                            NotificationDigestPreferences(
                                enabled = enabled,
                                frequency = frequency,
                                deliveryHour = deliveryHour,
                                deliveryWeekday = deliveryWeekday,
                                timezoneOffsetMinutes = timezoneOffsetMinutes,
                                includeMentions = includeMentions,
                                includeDirectMessages = includeDirectMessages,
                                includeCommunityActivity = includeCommunityActivity,
                                includeUpcomingLives = includeUpcomingLives,
                                lastDeliveredAt = current.overview.preferences.lastDeliveredAt,
                                updatedAt = current.overview.preferences.updatedAt
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NotificationDigestSettingsContent(
    modifier: Modifier,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    frequency: String,
    onFrequencyChange: (String) -> Unit,
    deliveryHour: Int,
    onDeliveryHourChange: (Int) -> Unit,
    deliveryWeekday: Int,
    onDeliveryWeekdayChange: (Int) -> Unit,
    includeMentions: Boolean,
    onIncludeMentionsChange: (Boolean) -> Unit,
    includeDirectMessages: Boolean,
    onIncludeDirectMessagesChange: (Boolean) -> Unit,
    includeCommunityActivity: Boolean,
    onIncludeCommunityActivityChange: (Boolean) -> Unit,
    includeUpcomingLives: Boolean,
    onIncludeUpcomingLivesChange: (Boolean) -> Unit,
    saving: Boolean,
    onSave: () -> Unit
) {
    val hasCategory = includeMentions ||
        includeDirectMessages ||
        includeCommunityActivity ||
        includeUpcomingLives
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsSwitchRow(
            title = "Recibir un resumen",
            description = "Agrupa actividad no urgente para reducir interrupciones.",
            checked = enabled,
            onCheckedChange = onEnabledChange
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 5.dp))
                    Text("Frecuencia y hora", fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = frequency == "daily",
                        onClick = { onFrequencyChange("daily") },
                        label = { Text("Diario") },
                        enabled = enabled
                    )
                    FilterChip(
                        selected = frequency == "weekly",
                        onClick = { onFrequencyChange("weekly") },
                        label = { Text("Semanal") },
                        enabled = enabled
                    )
                }
                DigestHourPicker(
                    hour = deliveryHour,
                    enabled = enabled,
                    onHourChange = onDeliveryHourChange
                )
                if (frequency == "weekly") {
                    DigestWeekdayPicker(
                        weekday = deliveryWeekday,
                        enabled = enabled,
                        onWeekdayChange = onDeliveryWeekdayChange
                    )
                }
                Text(
                    "Se usa la zona horaria de este teléfono.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text("Incluir en el resumen", style = MaterialTheme.typography.titleMedium)
        SettingsSwitchRow(
            title = "Menciones",
            description = "Cuando alguien te mencione en publicaciones o videos.",
            checked = includeMentions,
            onCheckedChange = onIncludeMentionsChange,
            enabled = enabled
        )
        SettingsSwitchRow(
            title = "Mensajes directos no urgentes",
            description = "Los mensajes marcados como urgentes siempre llegan de inmediato.",
            checked = includeDirectMessages,
            onCheckedChange = onIncludeDirectMessagesChange,
            enabled = enabled
        )
        SettingsSwitchRow(
            title = "Actividad de comunidades",
            description = "Mensajes y novedades de grupos o comunidades.",
            checked = includeCommunityActivity,
            onCheckedChange = onIncludeCommunityActivityChange,
            enabled = enabled
        )
        SettingsSwitchRow(
            title = "Directos próximos",
            description = "Recordatorios y actividad relacionada con transmisiones.",
            checked = includeUpcomingLives,
            onCheckedChange = onIncludeUpcomingLivesChange,
            enabled = enabled
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Shield, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 5.dp))
                Text(
                    "Llamadas, seguridad, suspensión o reactivación de cuenta, moderación y mensajes urgentes permanecen inmediatos.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (enabled && !hasCategory) {
            Text(
                "Elige al menos una categoría.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving && (!enabled || hasCategory),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("Guardar configuración")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun DigestHourPicker(
    hour: Int,
    enabled: Boolean,
    onHourChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Hora de entrega", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled
        ) {
            Text(String.format("%02d:00", hour))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (0..23).forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(String.format("%02d:00", candidate)) },
                    onClick = {
                        onHourChange(candidate)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DigestWeekdayPicker(
    weekday: Int,
    enabled: Boolean,
    onWeekdayChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Día de entrega", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled
        ) {
            Text(weekdayNames.getOrElse(weekday) { "Lunes" })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            weekdayNames.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onWeekdayChange(index)
                        expanded = false
                    }
                )
            }
        }
    }
}
