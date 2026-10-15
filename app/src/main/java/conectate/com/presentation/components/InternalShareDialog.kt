package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.User
import conectate.com.data.repository.UserRepository
import kotlinx.coroutines.launch

@Composable
fun InternalShareDialog(
    userRepository: UserRepository,
    onDismiss: () -> Unit,
    onShareWithUser: (User) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<User>>(emptyList()) }
    var selectedUser by remember { mutableStateOf<User?>(null) }
    LaunchedEffect(searchQuery) {
        selectedUser = null
        searchResults = emptyList()
        if (searchQuery.trim().length >= 2) {
            kotlinx.coroutines.delay(180)
            searchResults = userRepository.searchUsers(searchQuery.trim()).getOrDefault(emptyList()).deduplicateShareTargets()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compartir con...") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Buscar usuario") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(searchResults, key = { user -> user.id.ifBlank { user.email.ifBlank { user.displayName } } }) { user ->
                        val isSelected = selectedUser?.id == user.id
                        val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(backgroundColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { selectedUser = user }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProfileImage(openMomentOnClick = false, ownerId = user.id, 
                                imageUrl = user.avatar_url ?: "",
                                size = 40.dp,
                                isVerified = user.is_verified
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = user.displayName,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                val handle = conectate.com.data.model.profilePublicHandle(user)
                                Text(
                                    text = "@$handle",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    selectedUser?.let { onShareWithUser(it) }
                },
                enabled = selectedUser != null
            ) {
                Text("Enviar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

private fun List<User>.deduplicateShareTargets(): List<User> =
    filter { it.id.isNotBlank() }.distinctBy { it.id.trim().lowercase() }

// Helper to use MutableState in a more concise way if needed, but standard is fine
@Composable
fun <T> mutableStateFlowOf(initialValue: T): MutableState<T> = remember { mutableStateOf(initialValue) }
