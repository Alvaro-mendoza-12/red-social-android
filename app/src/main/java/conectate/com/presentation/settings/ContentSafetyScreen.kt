package conectate.com.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import conectate.com.data.local.prefs.ContentSafetyPreferences
import conectate.com.data.local.prefs.SensitiveContentMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ContentSafetyViewModel @Inject constructor(
    private val preferences: ContentSafetyPreferences
) : ViewModel() {
    val mode = preferences.sensitiveContentMode
    val adultConfirmed = preferences.adultConfirmed
    val moderationWarningsEnabled = preferences.moderationWarningsEnabled

    fun setMode(mode: SensitiveContentMode) = preferences.setSensitiveContentMode(mode)
    fun setAdultConfirmed(value: Boolean) = preferences.setAdultConfirmed(value)
    fun setModerationWarningsEnabled(value: Boolean) = preferences.setModerationWarningsEnabled(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentSafetyScreen(
    navController: NavController,
    viewModel: ContentSafetyViewModel = hiltViewModel()
) {
    val mode by viewModel.mode.collectAsState()
    val adultConfirmed by viewModel.adultConfirmed.collectAsState()
    val moderationWarningsEnabled by viewModel.moderationWarningsEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preferencias de contenido", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsCardLabel("Contenido sensible y +18")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    CompactSafetySwitch(
                        icon = Icons.Default.Block,
                        title = "Confirmo que soy mayor de 18 años",
                        subtitle = "No reemplaza la edad verificada de la cuenta.",
                        checked = adultConfirmed,
                        onCheckedChange = viewModel::setAdultConfirmed
                    )
                    SensitiveContentMode.entries.forEach { option ->
                        CompactSafetyChoice(
                            icon = Icons.Default.VisibilityOff,
                            title = option.label,
                            subtitle = when (option) {
                                SensitiveContentMode.HIDE -> "No mostrar contenido marcado como sensible."
                                SensitiveContentMode.BLUR -> "Mostrar una cubierta antes de abrirlo."
                                SensitiveContentMode.SHOW -> "Solo disponible después de confirmar +18."
                            },
                            selected = mode == option,
                            enabled = option != SensitiveContentMode.SHOW || adultConfirmed,
                            onClick = { viewModel.setMode(option) }
                        )
                    }
                }
            }

            SettingsCardLabel("Comentarios")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
            ) {
                CompactSafetySwitch(
                    icon = Icons.AutoMirrored.Filled.Comment,
                    title = "Avisarme cuando se censure texto",
                    subtitle = "El filtro siempre está activo; este ajuste controla el aviso visible.",
                    checked = moderationWarningsEnabled,
                    onCheckedChange = viewModel::setModerationWarningsEnabled
                )
            }

            Text(
                "Las amenazas, explotación infantil, enlaces maliciosos y spam se bloquean siempre, aunque desactives filtros visuales.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsCardLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CompactSafetySwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CompactSafetyChoice(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        }
    }
}
