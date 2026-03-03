package conectate.com.presentation.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose. foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import conectate.com.data.model.Badge
import conectate.com.data.model.User

@Composable
fun CreateBadgeDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, icon: String, color: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("🏆") }
    var color by remember { mutableStateOf("#FFD700") }
    var showIconPicker by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Crear Insignia Personalizada",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )

                // Icon Picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Icono:", style = MaterialTheme.typography.bodyMedium)
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { showIconPicker = !showIconPicker },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = icon, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }

                if (showIconPicker) {
                    val emojis = listOf("🏆", "⭐", "🌟", "💫", "✨", "🎖️", "🥇", "👑", "🔥", "⚡", "💎", "🎯", "🐛", "🤝", "🚀", "💪")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        emojis.take(8).forEach { emoji ->
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.clickable {
                                    icon = emoji
                                    showIconPicker = false
                                }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        emojis.drop(8).forEach { emoji ->
                            Text(
                                text = emoji,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.clickable {
                                    icon = emoji
                                    showIconPicker = false
                                }
                            )
                        }
                    }
                }

                // Color Picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Color:", style = MaterialTheme.typography.bodyMedium)
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { showColorPicker = !showColorPicker },
                        shape = CircleShape,
                        color = try {
                            Color(android.graphics.Color.parseColor(color))
                        } catch (e: Exception) {
                            MaterialTheme.colorScheme.primary
                        }
                    ) {}
                }

                if (showColorPicker) {
                    val colors = listOf(
                        "#FFD700" to "Dorado", "#FFA500" to "Naranja",
                        "#FF6347" to "Rojo", "#FF4500" to "Rojo Oscuro",
                        "#8B00FF" to "Morado", "#1DA1F2" to "Azul",
                        "#00CED1" to "Cyan", "#32CD32" to "Verde"
                    )
                    Column {
                        colors.chunked(4).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                row.forEach { (hex, _) ->
                                    Surface(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clickable {
                                                color = hex
                                                showColorPicker = false
                                            },
                                        shape = CircleShape,
                                        color = Color(android.graphics.Color.parseColor(hex))
                                    ) {}
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                // Preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = icon, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = name.ifBlank { "Nombre de insignia" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                onConfirm(name, description, icon, color)
                            }
                        },
                        enabled = name.isNotBlank()
                    ) {
                        Text("Crear")
                    }
                }
            }
        }
    }
}

@Composable
fun AssignBadgeDialog(
    user: User,
    availableBadges: List<Badge>,
    onDismiss: () -> Unit,
    onAssign: (badgeId: String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Otorgar Insignia",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Usuario: ${user.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Divider()

                if (availableBadges.isEmpty()) {
                    Text(
                        text = "No hay insignias disponibles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableBadges) { badge ->
                            BadgeListItem(
                                badge = badge,
                                onClick = {
                                    onAssign(badge.id)
                                }
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cerrar")
                }
            }
        }
    }
}

@Composable
fun BadgeListItem(
    badge: Badge,
    onClick: () -> Unit
) {
    val badgeColor = try {
        Color(android.graphics.Color.parseColor(badge.color))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = badgeColor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = badgeColor.copy(alpha = 0.3f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = badge.icon, style = MaterialTheme.typography.headlineSmall)
                }
            }

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = badge.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                badge.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}
