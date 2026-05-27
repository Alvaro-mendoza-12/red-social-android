@file:OptIn(ExperimentalMaterial3Api::class)

package conectate.com.presentation.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Close
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
import coil.compose.AsyncImage
import conectate.com.data.model.Badge
import conectate.com.data.model.User
import conectate.com.presentation.components.AdminBadge
import conectate.com.presentation.components.isUserAdmin



@Composable
fun BadgesTabContent(
    navController: NavController,
    viewModel: AdminViewModel = hiltViewModel()
) {
    var searchQuery by remember { mutableStateOf("") }
    var showCreateBadgeDialog by remember { mutableStateOf(false) }
    var showAssignBadgeDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    val searchedUser by viewModel.searchedUser.collectAsState()
    val allBadges by viewModel.allBadges.collectAsState()
    val userBadges by viewModel.userBadges.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.uiMessage.collect { message: String ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val grantedAssignments by viewModel.grantedAssignments.collectAsState()
    val eligibleUsers by viewModel.eligibleUsers.collectAsState()
    val currentThreshold by viewModel.currentThreshold.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val verifiedUsers by viewModel.verifiedUsers.collectAsState()

    // Grouping logic for the management section (Badges + Verification)
    val usersWithDetails = remember(grantedAssignments, verifiedUsers) {
        val badgeGroups = grantedAssignments
            .filter { it.user != null && it.badge != null }
            .groupBy { it.user_id }
            .mapValues { entry -> entry.value.mapNotNull { it.badge } }

        val allUserIds = (badgeGroups.keys + verifiedUsers.map { it.id }).distinct()
        
        allUserIds.mapNotNull { uid ->
            var user = verifiedUsers.find { it.id == uid } 
                       ?: grantedAssignments.find { it.user_id == uid }?.user
            
            if (user != null) {
                var badges = badgeGroups[uid] ?: emptyList()
                val finalVerified = user.is_verified || verifiedUsers.any { it.id == uid }
                user.copy(is_verified = finalVerified) to badges
            } else null
        }.sortedByDescending { it.second.size + (if (it.first.is_verified) 1 else 0) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                // Create Badge Button
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showCreateBadgeDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        } else {
                            Icon(Icons.Default.Add, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Crear Insignia")
                    }
                    
                    OutlinedButton(
                        onClick = { viewModel.seedSystemBadges() },
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Default.Star, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Insignias Top")
                    }
                }
            }

            item { Divider() }

            // User Search Section
            item {
                Text(
                    text = "Buscar Usuario",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { 
                        searchQuery = it
                        if (it.isNotBlank()) {
                            viewModel.searchUserByUsername(it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Nombre de usuario") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { 
                                searchQuery = ""
                                viewModel.clearSearchedUser()
                            }) {
                                Icon(Icons.Default.Clear, null)
                            }
                        }
                    },
                    singleLine = true
                )
            }

            // User Result Card
            searchedUser?.let { user ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // User Header
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AsyncImage(
                                    model = user.avatar_url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = user.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (user.is_admin || isUserAdmin(user.username, user.email)) {
                            Spacer(modifier = Modifier.width(4.dp))
                            AdminBadge(showVerifiedLine = false)
                        }
                        if (user.is_verified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Verificado",
                                tint = Color(0xFF1DA1F2),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                                   }
                                    user.username?.let {
                                        Text(
                                            text = "@$it",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Verified Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Verificado", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = user.is_verified,
                                    onCheckedChange = { 
                                        viewModel.setVerified(user.id, it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF1DA1F2),
                                        checkedTrackColor = Color(0xFF1DA1F2).copy(alpha = 0.5f)
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // User Badges
                            Text(
                                text = "Insignias actuales:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (userBadges.isEmpty()) {
                                Text(
                                    text = "Sin insignias",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            } else {
                                // Manual Row-based layout for user badges to avoid nested lazy columns issues if needed
                                // But since we are inside item, we can use a Fixed height Row or similar
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    userBadges.chunked(2).forEach { chunk ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            chunk.forEach { badge ->
                                                Box(modifier = Modifier.weight(1f)) {
                                                    BadgeChip(
                                                        badge = badge,
                                                        onClick = { viewModel.revokeBadge(user.id, badge.id) }
                                                    )
                                                }
                                            }
                                            if (chunk.size == 1) Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showAssignBadgeDialog = true },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isProcessing
                                ) {
                                    if (isProcessing) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                                    } else {
                                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Otorgar")
                                }
                            }
                        }
                    }
                }
            }

            item { Divider() }

            // NEW: Badge Management Section
            item {
                Text(
                    text = "Gestión de Usuarios con Insignias",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (usersWithDetails.isEmpty()) {
                item { 
                    Text(
                        "No hay insignias ni usuarios verificados actualmente", 
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) 
                }
            } else {
                items(usersWithDetails) { pair ->
                    val (user, badges) = pair
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            searchQuery = user.username ?: ""
                            viewModel.searchUserByUsername(searchQuery)
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = user.avatar_url,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.2f))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(user.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        if (user.is_verified) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp), tint = Color(0xFF2196F3))
                                        }
                                    }
                                    Text("@${user.username}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                                
                                IconButton(
                                    onClick = { viewModel.revokeAllBadges(user.id, badges) },
                                    enabled = !isProcessing
                                ) {
                                    Icon(Icons.Default.DeleteForever, "Borrar todo", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Badges and Verification flow-like layout
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Unified List of Tags (Verification is a special tag)
                                if (user.is_verified) {
                                    Surface(
                                        color = Color(0xFF2196F3).copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.clickable { viewModel.setVerified(user.id, false) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(10.dp), tint = Color(0xFF2196F3))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Verificado", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2196F3))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(Icons.Default.Close, null, modifier = Modifier.size(10.dp), tint = Color(0xFF2196F3))
                                        }
                                    }
                                }
                                
                                for (badge in badges) {
                                    Surface(
                                        color = Color.LightGray.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.clickable { viewModel.revokeBadge(user.id, badge.id) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(badge.icon, style = MaterialTheme.typography.bodySmall)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(badge.name, style = MaterialTheme.typography.labelSmall)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(Icons.Default.Close, null, modifier = Modifier.size(10.dp), tint = Color.Gray)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Divider() }

            // NEW: Eligibility Tracker (Followers)
            item {
                Text(
                    text = "Usuarios elegibles por seguidores",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                val thresholds = listOf(10_000, 50_000, 100_000, 500_000, 1_000_000, 10_000_000, 50_000_000)
                val labels = listOf("10k", "50k", "100k", "500k", "1M", "10M", "50M")
                
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    thresholds.zip(labels).forEach { pair ->
                        val (valThresh, label) = pair
                        FilterChip(
                            selected = currentThreshold == valThresh,
                            onClick = { 
                                if (currentThreshold == valThresh) viewModel.clearEligibleUsers()
                                else viewModel.loadEligibleUsers(valThresh)
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }

            if (eligibleUsers.isNotEmpty()) {
                items(eligibleUsers) { user ->
                    ListItem(
                        headlineContent = { Text(user.displayName) },
                        supportingContent = { Text("${user.followers_count ?: 0} seguidores") },
                        leadingContent = {
                            AsyncImage(
                                model = user.avatar_url,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape)
                            )
                        },
                        trailingContent = {
                            Button(onClick = {
                                searchQuery = user.username ?: ""
                                viewModel.searchUserByUsername(searchQuery)
                            }) {
                                Text("Ver")
                            }
                        }
                    )
                }
            } else if (currentThreshold != null) {
                item { Text("No hay usuarios con este nivel", style = MaterialTheme.typography.bodySmall) }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // Create Badge Dialog
    if (showCreateBadgeDialog) {
        CreateBadgeDialog(
            onDismiss = { showCreateBadgeDialog = false },
            onConfirm = { name, description, icon, color ->
                viewModel.createCustomBadge(name, description, icon, color)
                showCreateBadgeDialog = false
            }
        )
    }

    // Assign Badge Dialog
    if (showAssignBadgeDialog) {
        var user = searchedUser
        if (user != null) {
            AssignBadgeDialog(
                user = user,
                availableBadges = allBadges,
                onDismiss = { showAssignBadgeDialog = false },
                onAssign = { badgeId ->
                    viewModel.grantBadge(user.id, badgeId)
                    showAssignBadgeDialog = false
                }
            )
        }
    }
}


@Composable
fun BadgeChip(
    badge: Badge,
    onClick: () -> Unit = {}
) {
    val badgeColor = try {
        Color(android.graphics.Color.parseColor(badge.color))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = badgeColor.copy(alpha = 0.2f),
        shape = RoundedCornerShape(8.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = badge.icon,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = badge.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (badge.isAdminBadge) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}







