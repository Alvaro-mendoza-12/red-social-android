package conectate.com.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import conectate.com.data.local.prefs.AppFontOption
import conectate.com.presentation.theme.appFontFamilyFor
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppAppearanceSettingsSection(
    isDarkMode: Boolean,
    fontOption: AppFontOption,
    textScale: Float,
    onDarkModeChanged: (Boolean) -> Unit,
    onFontOptionChanged: (AppFontOption) -> Unit,
    onTextScaleChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Apariencia",
    subtitle: String = "Personaliza tema, tipografía y tamaño de texto en toda la app.",
    showHeader: Boolean = true
) {
    var showFontCatalog by remember { mutableStateOf(false) }
    val quickFonts = remember {
        listOf(
            AppFontOption.DEFAULT,
            AppFontOption.SANS_SERIF,
            AppFontOption.ROUNDED,
            AppFontOption.SERIF,
            AppFontOption.MONOSPACE
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showHeader) {
            AppearanceSectionHeader(
                title = title,
                subtitle = subtitle
            )
        }

        AppearanceToggleRow(
            title = "Modo oscuro",
            subtitle = "Cambia el tema general de la aplicación.",
            checked = isDarkMode,
            onCheckedChange = onDarkModeChanged
        )

        AppearanceSectionHeader(
            title = "Tipografía",
            subtitle = "La fuente elegida se usa en toda la aplicación."
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickFonts.forEach { option ->
                FilterChip(
                    selected = fontOption == option,
                    onClick = { onFontOptionChanged(option) },
                    label = {
                        Text(
                            text = option.label,
                            fontFamily = appFontFamilyFor(option.storageValue),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
            AssistChip(
                onClick = { showFontCatalog = true },
                label = { Text("Más") }
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Vista previa",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Así se verán tus mensajes y encabezados en la app.",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = appFontFamilyFor(fontOption.storageValue)
            )
            Text(
                text = "Algunas fuentes dependen del sistema del dispositivo y usan fallback seguro si no están disponibles.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Tamaño del texto ${(textScale * 100).roundToInt()}%",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = textScale,
            onValueChange = onTextScaleChanged,
            valueRange = 0.9f..1.2f,
            steps = 5
        )
        Text(
            text = "La escala aumenta el texto sin deformar botones ni contenedores.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showFontCatalog) {
        FontCatalogDialog(
            selectedOption = fontOption,
            onDismiss = { showFontCatalog = false },
            onFontSelected = {
                onFontOptionChanged(it)
                showFontCatalog = false
            }
        )
    }
}

@Composable
private fun FontCatalogDialog(
    selectedOption: AppFontOption,
    onDismiss: () -> Unit,
    onFontSelected: (AppFontOption) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Más tipografías") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppFontOption.entries.forEach { option ->
                    FontCatalogRow(
                        option = option,
                        selected = selectedOption == option,
                        onClick = { onFontSelected(option) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

@Composable
private fun FontCatalogRow(
    option: AppFontOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp)
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = appFontFamilyFor(option.storageValue),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Conectate 123",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = appFontFamilyFor(option.storageValue),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppearanceSectionHeader(
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppearanceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
