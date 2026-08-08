@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package conectate.com.presentation.socialization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.model.SocializationPreferenceKey

/**
 * Voluntary, consent-first controls for future Socializar surfaces.
 * This screen cannot create a conversation, match people, or expose data.
 */
@Composable
fun SocializationSettingsScreen(
    navController: NavController,
    viewModel: SocializationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.error, state.message) {
        (state.error ?: state.message)?.let { snackbarHost.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Socializar") },
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isLoading && !state.isSaving) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar preferencias")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        when {
            state.isLoading && !state.usingCachedPreferences -> LoadingContent(Modifier.padding(padding))
            !state.capabilities.enabled -> DisabledContent(Modifier.padding(padding), state.usingCachedPreferences)
            else -> ConsentContent(
                modifier = Modifier.padding(padding),
                state = state,
                onConsentChange = viewModel::setConsent,
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text("Cargando tus controles…", modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun DisabledContent(modifier: Modifier, hasCache: Boolean) {
    LazyColumn(
        modifier = modifier.fillMaxSize().navigationBarsPadding().imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Socializar, siempre voluntario", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Esta experiencia está preparada, pero desactivada durante el despliegue gradual. " +
                            "No se crearán conversaciones, salas ni coincidencias automáticamente.",
                    )
                    Text(
                        "Nunca usa mensajes privados, ubicación precisa, edad ni género para recomendar personas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (hasCache) {
                        Text(
                            "Se conserva la última configuración confirmada por el servidor.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentContent(
    modifier: Modifier,
    state: SocializationSettingsUiState,
    onConsentChange: (SocializationPreferenceKey, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().navigationBarsPadding().imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Tú decides cómo participar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Estas opciones sólo autorizan experiencias públicas y seguras cuando estén disponibles. " +
                            "Puedes salir, bloquear o reportar siempre que una experiencia lo permita.",
                    )
                    if (state.usingCachedPreferences) {
                        Text("Sin conexión: mostrando la última versión confirmada.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.isSaving) Text("Guardando…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text("Permisos de participación", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Activar una opción no te conecta con nadie por sí solo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(SocializationPreferenceKey.entries.size) { index ->
            val key = SocializationPreferenceKey.entries[index]
            ConsentPreferenceCard(
                key = key,
                checked = state.preferences.isEnabled(key),
                featureAvailable = state.capabilities.features.isEnabled(key),
                enabled = state.canEditConsent,
                onChange = { onConsentChange(key, it) },
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Protecciones", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Las recomendaciones sociales no usan mensajes privados, GPS preciso ni atributos sensibles. " +
                            "El chat aleatorio no se activa desde esta pantalla.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsentPreferenceCard(
    key: SocializationPreferenceKey,
    checked: Boolean,
    featureAvailable: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(key.label, fontWeight = FontWeight.SemiBold)
                Text(key.description, style = MaterialTheme.typography.bodySmall)
                if (!featureAvailable) {
                    Text(
                        "Tu autorización se guardará; la función aún no está activa para tu cuenta.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
            )
        }
    }
}
