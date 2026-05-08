package conectate.com.presentation.social

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import conectate.com.presentation.components.CircularProfileImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendRequestsScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Solicitudes de Amistad", fontWeight = FontWeight.Bold) },
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
                .padding(paddingValues)
        ) {
            items(10) { index ->
                ListItem(
                    headlineContent = { Text("Usuario Solicitante $index") },
                    supportingContent = { Text("Quiere ser tu amigo") },
                    leadingContent = {
                        CircularProfileImage(imageUrl = null, size = 48.dp)
                    },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { /* Accept */ }) {
                                Text("Aceptar")
                            }
                            OutlinedButton(onClick = { /* Reject */ }) {
                                Text("Rechazar")
                            }
                        }
                    }
                )
                Divider()
            }
        }
    }
}
