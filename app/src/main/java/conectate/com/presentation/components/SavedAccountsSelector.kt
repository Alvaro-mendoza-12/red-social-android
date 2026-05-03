package conectate.com.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import conectate.com.data.model.SavedAccount

@Composable
fun SavedAccountsSelector(
    accounts: List<SavedAccount>,
    onAccountSelected: (SavedAccount) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (SavedAccount) -> Unit,
    modifier: Modifier = Modifier
) {
    if (accounts.isEmpty()) return
    
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Cuentas guardadas",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(accounts, key = { it.userId }) { account ->
                SavedAccountChip(
                    account = account,
                    onClick = { onAccountSelected(account) },
                    onRemove = { onRemoveAccount(account) }
                )
            }
            
            item {
                AddAccountChip(onClick = onAddAccount)
            }
        }
    }
}

@Composable
private fun SavedAccountChip(
    account: SavedAccount,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar with online indicator
            Box {
                AsyncImage(
                    model = account.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
                
                // Online status indicator
                if (account.isOnline) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                            .align(Alignment.BottomEnd)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Name
            Text(
                text = account.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            // Email/Phone hint
            Text(
                text = account.emailHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Last used info
            Text(
                text = "Usada ${account.lastUsedRelative}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            // Menu button
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Opciones",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
    
    DropdownMenu(
        expanded = showMenu,
        onDismissRequest = { showMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text("Eliminar cuenta guardada") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            onClick = {
                showMenu = false
                onRemove()
            }
        )
    }
}

@Composable
private fun AddAccountChip(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(100.dp)
            .height(140.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Agregar cuenta",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun AccountSwitcherBottomSheet(
    accounts: List<SavedAccount>,
    currentAccountId: String?,
    onAccountSelected: (SavedAccount) -> Unit,
    onAddAccount: () -> Unit,
    onManageAccounts: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Handle bar
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        .align(Alignment.CenterHorizontally)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Cambiar cuenta",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                accounts.forEach { account ->
                    val isCurrent = account.userId == currentAccountId
                    
                    ListItem(
                        headlineContent = {
                            Text(
                                account.displayName,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        supportingContent = { Text(account.emailHint) },
                        leadingContent = {
                            Box {
                                AsyncImage(
                                    model = account.avatarUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                )
                                if (account.isOnline) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF4CAF50))
                                            .align(Alignment.BottomEnd)
                                    )
                                }
                            }
                        },
                        trailingContent = if (isCurrent) {
                            {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        modifier = Modifier.clickable {
                            if (!isCurrent) {
                                onAccountSelected(account)
                            }
                        }
                    )
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Add account option
                ListItem(
                    headlineContent = { Text("Agregar otra cuenta") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable(onClick = onAddAccount)
                )
                
                // Manage accounts option
                ListItem(
                    headlineContent = { Text("Gestionar cuentas") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable(onClick = onManageAccounts)
                )
            }
        }
    }
}

@Composable
fun QuickAccountSwitchFAB(
    accounts: List<SavedAccount>,
    currentAccountId: String?,
    onAccountSwitch: (SavedAccount) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    val otherAccounts = accounts.filter { it.userId != currentAccountId }
    
    if (otherAccounts.isEmpty()) return
    
    Box(modifier = modifier) {
        SmallFloatingActionButton(
            onClick = { expanded = true },
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ) {
            AsyncImage(
                model = otherAccounts.first().avatarUrl,
                contentDescription = "Cambiar cuenta",
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
            )
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            otherAccounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text("Cambiar a ${account.displayName}") },
                    leadingContent = {
                        AsyncImage(
                            model = account.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                        )
                    },
                    onClick = {
                        expanded = false
                        onAccountSwitch(account)
                    }
                )
            }
        }
    }
}
