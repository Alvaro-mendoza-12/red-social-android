package conectate.com.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.local.prefs.AppSettingsPreferences
import conectate.com.presentation.profile.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeAppScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val savedLayout by viewModel.bottomNavigationLayout.collectAsState()
    val savedTabs by viewModel.bottomNavigationOrder.collectAsState()
    var selectedLayout by remember(savedLayout) { mutableStateOf(savedLayout) }
    var customTabs by remember(savedTabs) {
        mutableStateOf(savedTabs.ifEmpty { AppSettingsPreferences.DEFAULT_BOTTOM_NAV_ORDER })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personalizar App") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Text(
                    text = "DISEÑOS PREDEFINIDOS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                LayoutOption(
                    title = "Clásico",
                    subtitle = "Mensajes • Videos • Social • Perfil",
                    isSelected = selectedLayout == 0,
                    onClick = { 
                        selectedLayout = 0 
                        customTabs = listOf("Mensajes", "Videos", "Social", "Perfil")
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                LayoutOption(
                    title = "Videos Primero (Estilo TikTok)",
                    subtitle = "Videos • Mensajes • Social • Perfil",
                    isSelected = selectedLayout == 1,
                    onClick = { 
                        selectedLayout = 1 
                        customTabs = listOf("Videos", "Mensajes", "Social", "Perfil")
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                LayoutOption(
                    title = "Social Focus (Estilo Instagram)",
                    subtitle = "Social • Videos • Mensajes • Perfil",
                    isSelected = selectedLayout == 2,
                    onClick = { 
                        selectedLayout = 2 
                        customTabs = listOf("Social", "Videos", "Mensajes", "Perfil")
                    }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            item {
                Text(
                    text = "ROMPECABEZAS (ORDEN PERSONALIZADO)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Reordena la barra inferior. El cambio se guarda y se aplica al volver a Inicio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            items(customTabs.size) { index ->
                val tabName = customTabs[index]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${index + 1}. $tabName",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Row {
                            IconButton(
                                onClick = {
                                    customTabs = customTabs.moveItem(index, index - 1)
                                    selectedLayout = 3
                                },
                                enabled = index > 0
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Subir $tabName"
                                )
                            }
                            IconButton(
                                onClick = {
                                    customTabs = customTabs.moveItem(index, index + 1)
                                    selectedLayout = 3
                                },
                                enabled = index < customTabs.lastIndex
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Bajar $tabName"
                                )
                            }
                        }
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        viewModel.setBottomNavigation(selectedLayout, customTabs)
                        navController.popBackStack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Guardar Diseño")
                }
            }
        }
    }
}

private fun List<String>.moveItem(fromIndex: Int, toIndex: Int): List<String> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    return toMutableList().apply {
        val item = removeAt(fromIndex)
        add(toIndex, item)
    }
}

@Composable
fun LayoutOption(title: String, subtitle: String, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clip(RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = "Seleccionado", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
