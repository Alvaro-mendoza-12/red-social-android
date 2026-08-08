@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package conectate.com.presentation.algorithm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.model.AlgorithmDataPermissions
import conectate.com.data.model.AlgorithmDimension
import conectate.com.data.model.AlgorithmFeedMix
import conectate.com.data.model.AlgorithmMode
import conectate.com.data.model.AlgorithmPreference
import conectate.com.data.model.AlgorithmPreferenceInput
import conectate.com.data.model.AlgorithmSurface
import java.text.Normalizer

/**
 * Visible editor for user-owned feed rules. It is deliberately not a ranking
 * engine: every write waits for the backend's revision-controlled confirmation.
 */
@Composable
fun AlgorithmSettingsScreen(
    navController: NavController,
    viewModel: AlgorithmSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var showRuleEditor by remember { mutableStateOf<AlgorithmPreference?>(null) }
    var showNewRuleEditor by remember { mutableStateOf(false) }
    var showMixEditor by remember { mutableStateOf<AlgorithmFeedMix?>(null) }
    var showCreateProfile by remember { mutableStateOf(false) }

    LaunchedEffect(state.error, state.message) {
        (state.error ?: state.message)?.let { snackbarHost.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mi algoritmo") },
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh, enabled = !state.isLoading && !state.isSaving) {
                        Text("Actualizar")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        when {
            state.isLoading && state.config == null -> LoadingContent(Modifier.padding(padding))
            !state.isFeatureAvailable -> AlgorithmUnavailableContent(
                modifier = Modifier.padding(padding),
                hasCachedConfiguration = state.config != null,
            )
            else -> AlgorithmContent(
                modifier = Modifier.padding(padding),
                state = state,
                canEditPreferences = state.capabilities.preferencesEnabled,
                canEditFeedMix = state.capabilities.feedMixEnabled,
                canEditDataPermissions = state.capabilities.dataPermissionsEnabled,
                onSelectProfile = viewModel::selectProfile,
                onCreateProfile = { showCreateProfile = true },
                onSetEnabled = viewModel::setProfileEnabled,
                onSetMode = viewModel::setMode,
                onMakeDefault = viewModel::makeDefault,
                onAddRule = { showNewRuleEditor = true },
                onEditRule = { showRuleEditor = it },
                onDeleteRule = viewModel::deletePreference,
                onEditMix = { showMixEditor = it },
                onUpdatePermissions = viewModel::updateDataPermissions,
                onDeleteTemporary = viewModel::deleteTemporaryPreference,
            )
        }
    }

    if (showNewRuleEditor || showRuleEditor != null) {
        PreferenceEditorDialog(
            existing = showRuleEditor,
            onDismiss = {
                showNewRuleEditor = false
                showRuleEditor = null
            },
            onSave = { input, durationHours ->
                if (durationHours == null) viewModel.upsertPreference(input)
                else viewModel.addTemporaryPreference(input, durationHours)
                showNewRuleEditor = false
                showRuleEditor = null
            },
        )
    }
    showMixEditor?.let { mix ->
        FeedMixEditorDialog(
            initial = mix,
            onDismiss = { showMixEditor = null },
            onSave = {
                viewModel.updateFeedMix(it)
                showMixEditor = null
            },
        )
    }
    if (showCreateProfile) {
        CreateProfileDialog(
            onDismiss = { showCreateProfile = false },
            onCreate = { name, mode ->
                viewModel.createProfile(name, mode)
                showCreateProfile = false
            },
        )
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Cargando tu configuración…", modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun AlgorithmUnavailableContent(modifier: Modifier, hasCachedConfiguration: Boolean) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tu algoritmo, bajo tu control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Esta función está preparada pero desactivada durante el despliegue gradual. " +
                            "Cuando se habilite podrás decidir temas, creadores, formatos y mezcla de cada feed.",
                    )
                    Text(
                        "Nunca usa mensajes privados, ubicación precisa, fecha de nacimiento ni género.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (hasCachedConfiguration) {
                        AssistChip(onClick = {}, label = { Text("Se conserva la última configuración confirmada") })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlgorithmContent(
    modifier: Modifier,
    state: AlgorithmSettingsUiState,
    canEditPreferences: Boolean,
    canEditFeedMix: Boolean,
    canEditDataPermissions: Boolean,
    onSelectProfile: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetMode: (String) -> Unit,
    onMakeDefault: () -> Unit,
    onAddRule: () -> Unit,
    onEditRule: (AlgorithmPreference) -> Unit,
    onDeleteRule: (AlgorithmPreference) -> Unit,
    onEditMix: (AlgorithmFeedMix) -> Unit,
    onUpdatePermissions: (AlgorithmDataPermissions) -> Unit,
    onDeleteTemporary: (AlgorithmPreference) -> Unit,
) {
    val config = state.config ?: return
    val profile = config.profile
    val orderedRules = config.preferences.sortedWith(
        compareBy<AlgorithmPreference> { it.score >= 0 }.thenBy { it.subjectLabel ?: it.subjectKey },
    )
    val positiveRules = orderedRules.filter { it.score >= 0 }
    val negativeRules = orderedRules.filter { it.score < 0 }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HeaderCard(
                profileName = profile.name,
                isCached = state.usingCachedConfiguration,
                isSaving = state.isSaving,
                enabled = profile.isEnabled,
                canChangeEnabled = canEditPreferences,
                onSetEnabled = onSetEnabled,
            )
        }
        item {
            SectionTitle("Perfiles", "Cambia de contexto sin perder tus reglas.")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.profiles, key = { it.id }) { candidate ->
                    FilterChip(
                        selected = candidate.id == profile.id,
                        onClick = { if (canEditPreferences) onSelectProfile(candidate.id) },
                        enabled = canEditPreferences,
                        label = { Text(candidate.name) },
                    )
                }
                item {
                    AssistChip(
                        onClick = onCreateProfile,
                        enabled = canEditPreferences,
                        label = { Text("+ Crear") },
                    )
                }
            }
        }
        item {
            SectionTitle("Modo de aprendizaje", "Las reglas explícitas siempre ganan.")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AlgorithmMode.entries) { mode ->
                    FilterChip(
                        selected = profile.mode == mode.wireName,
                        onClick = { if (canEditPreferences) onSetMode(mode.wireName) },
                        enabled = canEditPreferences,
                        label = { Text(modeLabel(mode)) },
                    )
                }
            }
            Text(
                modeDescription(AlgorithmMode.fromWire(profile.mode)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (!profile.isDefault) {
                TextButton(onClick = onMakeDefault, enabled = canEditPreferences) {
                    Text("Usar como perfil predeterminado")
                }
            }
        }
        item {
            SectionHeaderButton(
                "Mis intereses",
                "Añade reglas separadas para tema, creador, formato, audio, idioma o comunidad.",
                "Añadir regla",
                enabled = canEditPreferences,
                onClick = onAddRule,
            )
        }
        if (positiveRules.isEmpty()) {
            item { EmptyCard("Todavía no añadiste reglas. Puedes empezar con algo como “Programación: prioridad alta”.") }
        } else {
            items(positiveRules, key = { "preference-${it.id}" }) { rule ->
                PreferenceCard(
                    rule,
                    enabled = canEditPreferences,
                    onEdit = { onEditRule(rule) },
                    onDelete = { onDeleteRule(rule) },
                )
            }
        }
        item {
            SectionTitle("No quiero ver", "Estas reglas tienen prioridad sobre cualquier señal aprendida.")
        }
        if (negativeRules.isEmpty()) {
            item { EmptyCard("No has bloqueado ni reducido ningún tema o creador.") }
        } else {
            items(negativeRules, key = { "negative-${it.id}" }) { rule ->
                PreferenceCard(
                    rule,
                    enabled = canEditPreferences,
                    onEdit = { onEditRule(rule) },
                    onDelete = { onDeleteRule(rule) },
                )
            }
        }
        item {
            SectionTitle("Composición del feed", "La mezcla se cumple de forma flexible durante la sesión cuando haya contenido suficiente.")
        }
        items(config.feedMixes, key = { "mix-${it.surface}" }) { mix ->
            FeedMixCard(mix, enabled = canEditFeedMix, onEdit = { onEditMix(mix) })
        }
        item {
            DataPermissionsCard(
                permissions = config.dataPermissions,
                enabled = canEditDataPermissions,
                onUpdate = onUpdatePermissions,
            )
        }
        item {
            SectionTitle("Preferencias temporales", "Caducan automáticamente; no hay que recordar quitarlas.")
        }
        if (config.temporaryPreferences.isEmpty()) {
            item { EmptyCard("No hay instrucciones temporales activas.") }
        } else {
            items(config.temporaryPreferences, key = { "temporary-${it.id}" }) { rule ->
                TemporaryPreferenceCard(
                    rule,
                    enabled = canEditPreferences,
                    onDelete = { onDeleteTemporary(rule) },
                )
            }
        }
        if (!canEditPreferences || !canEditFeedMix || !canEditDataPermissions) {
            item {
                EmptyCard(
                    "Algunos controles se mostrarán como solo lectura hasta que se habiliten para tu grupo de pruebas. " +
                        "Tus cambios no se guardarán localmente sin confirmación del servidor.",
                )
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Datos y explicaciones", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cuando el ranking autoritativo se habilite, Videos y Social mostrarán razones reales como tus reglas, personas seguidas y la composición seleccionada. " +
                            "Nunca se inventarán explicaciones.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(
    profileName: String,
    isCached: Boolean,
    isSaving: Boolean,
    enabled: Boolean,
    canChangeEnabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Feed: $profileName", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Diseña lo que quieres ver; Novvin no cambia tus reglas explícitas.")
                if (isCached) Text("Sin conexión: mostrando la última versión confirmada.", style = MaterialTheme.typography.bodySmall)
                if (isSaving) Text("Guardando…", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onSetEnabled,
                enabled = canChangeEnabled && !isSaving,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeaderButton(
    title: String,
    subtitle: String,
    action: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { SectionTitle(title, subtitle) }
        TextButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PreferenceCard(
    rule: AlgorithmPreference,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = { if (enabled) onEdit() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(rule.subjectLabel ?: rule.subjectKey, fontWeight = FontWeight.SemiBold)
                Text(
                    "${AlgorithmDimension.fromWire(rule.dimension).label} · ${AlgorithmSurface.fromWire(rule.surface).label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(preferenceSummary(rule), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDelete, enabled = enabled) { Text("Quitar") }
        }
    }
}

@Composable
private fun TemporaryPreferenceCard(rule: AlgorithmPreference, enabled: Boolean, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(rule.subjectLabel ?: rule.subjectKey, fontWeight = FontWeight.SemiBold)
                Text("Hasta ${rule.expiresAt ?: "que caduque"}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDelete, enabled = enabled) { Text("Cancelar") }
        }
    }
}

@Composable
private fun FeedMixCard(mix: AlgorithmFeedMix, enabled: Boolean, onEdit: () -> Unit) {
    Card(onClick = { if (enabled) onEdit() }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(AlgorithmSurface.fromWire(mix.surface).label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit, enabled = enabled) { Text("Editar") }
            }
            Text(
                "Seguidos ${mix.following}% · Intereses ${mix.interests}% · Nuevo ${mix.newContent}% · Tendencias ${mix.trends}% · Explorar ${mix.exploration}%",
                style = MaterialTheme.typography.bodySmall,
            )
            if (mix.communities > 0) Text("Comunidades ${mix.communities}%", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DataPermissionsCard(
    permissions: AlgorithmDataPermissions,
    enabled: Boolean,
    onUpdate: (AlgorithmDataPermissions) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Datos utilizados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Elige las señales públicas o de uso que pueden ayudar a recomendar. Los mensajes privados nunca se usan.", style = MaterialTheme.typography.bodySmall)
            Divider(Modifier.padding(vertical = 4.dp))
            PermissionToggle("Likes", permissions.likes, enabled) { onUpdate(permissions.copy(likes = it)) }
            PermissionToggle("Personas seguidas", permissions.follows, enabled) { onUpdate(permissions.copy(follows = it)) }
            PermissionToggle("Búsquedas", permissions.searches, enabled) { onUpdate(permissions.copy(searches = it)) }
            PermissionToggle("Tiempo de visualización", permissions.watchTime, enabled) { onUpdate(permissions.copy(watchTime = it)) }
            PermissionToggle("Velocidad de desplazamiento", permissions.scrollVelocity, enabled) { onUpdate(permissions.copy(scrollVelocity = it)) }
            PermissionToggle("Perfiles visitados", permissions.profileVisits, enabled) { onUpdate(permissions.copy(profileVisits = it)) }
            PermissionToggle("Interacciones públicas", permissions.publicInteractions, enabled) { onUpdate(permissions.copy(publicInteractions = it)) }
            PermissionToggle("Actividad en comunidades", permissions.communityActivity, enabled) { onUpdate(permissions.copy(communityActivity = it)) }
            PermissionToggle("Actividad en directos", permissions.liveActivity, enabled) { onUpdate(permissions.copy(liveActivity = it)) }
        }
    }
}

@Composable
private fun PermissionToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun PreferenceEditorDialog(
    existing: AlgorithmPreference?,
    onDismiss: () -> Unit,
    onSave: (AlgorithmPreferenceInput, Long?) -> Unit,
) {
    var subject by remember(existing?.id) { mutableStateOf(existing?.subjectLabel ?: existing?.subjectKey.orEmpty()) }
    var surface by remember(existing?.id) { mutableStateOf(AlgorithmSurface.fromWire(existing?.surface)) }
    var dimension by remember(existing?.id) { mutableStateOf(AlgorithmDimension.fromWire(existing?.dimension)) }
    var score by remember(existing?.id) { mutableIntStateOf(existing?.score ?: 70) }
    var temporary by remember(existing?.id) { mutableStateOf(existing?.isTemporary == true) }
    var duration by remember(existing?.id) { mutableStateOf(24L) }
    val valid = subject.trim().length >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nueva regla" else "Editar regla") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it.take(80) },
                        label = { Text("Tema, creador o formato") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    Text("Qué quieres cambiar", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(AlgorithmDimension.entries) { option ->
                            FilterChip(selected = dimension == option, onClick = { dimension = option }, label = { Text(option.label) })
                        }
                    }
                }
                item {
                    Text("Dónde aplica", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(AlgorithmSurface.entries) { option ->
                            FilterChip(selected = surface == option, onClick = { surface = option }, label = { Text(option.label) })
                        }
                    }
                }
                item {
                    Text("${scoreLabel(score)} (${if (score > 0) "+" else ""}$score)", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = score.toFloat(),
                        onValueChange = { score = it.toInt().coerceIn(-100, 100) },
                        valueRange = -100f..100f,
                        steps = 19,
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Solo temporalmente", modifier = Modifier.weight(1f))
                        Switch(checked = temporary, onCheckedChange = { temporary = it })
                    }
                    if (temporary) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf(1L, 24L, 72L, 24L * 7, 24L * 30)) { hours ->
                                FilterChip(
                                    selected = duration == hours,
                                    onClick = { duration = hours },
                                    label = { Text(durationLabel(hours)) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    val key = subjectKeyFor(subject)
                    onSave(
                        AlgorithmPreferenceInput(
                            surface = surface.wireName,
                            dimension = dimension.wireName,
                            subjectKey = key.ifBlank { "preferencia" },
                            subjectLabel = subject.trim(),
                            score = score,
                        ),
                        if (temporary) duration else null,
                    )
                },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun FeedMixEditorDialog(initial: AlgorithmFeedMix, onDismiss: () -> Unit, onSave: (AlgorithmFeedMix) -> Unit) {
    var following by remember(initial.surface) { mutableStateOf(initial.following.toString()) }
    var interests by remember(initial.surface) { mutableStateOf(initial.interests.toString()) }
    var communities by remember(initial.surface) { mutableStateOf(initial.communities.toString()) }
    var newContent by remember(initial.surface) { mutableStateOf(initial.newContent.toString()) }
    var trends by remember(initial.surface) { mutableStateOf(initial.trends.toString()) }
    var exploration by remember(initial.surface) { mutableStateOf(initial.exploration.toString()) }
    fun value(text: String) = text.toIntOrNull()?.coerceIn(0, 100) ?: 0
    val total = value(following) + value(interests) + value(communities) + value(newContent) + value(trends) + value(exploration)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mezcla: ${AlgorithmSurface.fromWire(initial.surface).label}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("El total debe ser 100%. Si falta contenido de una categoría, Novvin redistribuye temporalmente sin cambiar tus preferencias.") }
                item { MixField("Personas seguidas", following) { following = it } }
                item { MixField("Mis intereses", interests) { interests = it } }
                item { MixField("Comunidades", communities) { communities = it } }
                item { MixField("Contenido nuevo", newContent) { newContent = it } }
                item { MixField("Tendencias", trends) { trends = it } }
                item { MixField("Exploración", exploration) { exploration = it } }
                item {
                    Text(
                        "Total: $total%",
                        color = if (total == 100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = total == 100,
                onClick = {
                    onSave(initial.copy(
                        following = value(following), interests = value(interests), communities = value(communities),
                        newContent = value(newContent), trends = value(trends), exploration = value(exploration),
                    ))
                },
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun MixField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        suffix = { Text("%") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CreateProfileDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AlgorithmMode.MANUAL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crear un algoritmo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ejemplos: Estudiar, Relajarme o Descubrir.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(AlgorithmMode.entries) { option ->
                        FilterChip(selected = mode == option, onClick = { mode = option }, label = { Text(modeLabel(option)) })
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = name.trim().length >= 2, onClick = { onCreate(name.trim(), mode.wireName) }) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun preferenceSummary(rule: AlgorithmPreference): String = when {
    rule.score <= -100 -> "No mostrar"
    rule.score < 0 -> "Mostrar poco (${rule.score}%)"
    rule.score >= 70 -> "Prioridad alta (${rule.score}%)"
    else -> "Prioridad normal (${rule.score}%)"
}

private fun scoreLabel(score: Int): String = when {
    score <= -100 -> "No mostrar"
    score < 0 -> "Mostrar poco"
    score >= 70 -> "Prioridad alta"
    else -> "Prioridad normal"
}

private fun durationLabel(hours: Long): String = when (hours) {
    1L -> "1 hora"
    24L -> "Hoy"
    72L -> "3 días"
    168L -> "7 días"
    else -> "30 días"
}

private fun modeLabel(mode: AlgorithmMode): String = when (mode) {
    AlgorithmMode.MANUAL -> "Manual"
    AlgorithmMode.ASSISTED -> "Asistido"
    AlgorithmMode.AUTOMATIC -> "Automático"
}

private fun modeDescription(mode: AlgorithmMode): String = when (mode) {
    AlgorithmMode.MANUAL -> "No se modifican tus intereses con tu comportamiento."
    AlgorithmMode.ASSISTED -> "Novvin puede proponer cambios; tú siempre los apruebas."
    AlgorithmMode.AUTOMATIC -> "Puede usar sólo las señales de datos que autorices, sin superar tus reglas explícitas."
}

private fun subjectKeyFor(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(80)
