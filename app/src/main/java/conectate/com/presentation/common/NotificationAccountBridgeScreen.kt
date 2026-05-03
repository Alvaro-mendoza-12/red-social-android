package conectate.com.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import conectate.com.presentation.navigation.Screen


@Composable
fun NotificationAccountBridgeScreen(
    navController: NavController,
    destinationRoute: String,
    targetAccountId: String?,
    targetAccountLabel: String?,
    currentAccountLabel: String?,
    contextTitle: String?,
    preview: String?,
    viewModel: NotificationAccountBridgeViewModel = hiltViewModel()
) {
    val isSwitching by viewModel.isSwitching.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val completedRoute by viewModel.completedRoute.collectAsState()

    val resolvedCurrentLabel = currentAccountLabel?.takeIf { it.isNotBlank() } ?: "tu cuenta actual"
    val resolvedTargetLabel = targetAccountLabel?.takeIf { it.isNotBlank() } ?: "esta cuenta"
    val hasStoredTarget = !targetAccountId.isNullOrBlank()

    LaunchedEffect(completedRoute) {
        val route = completedRoute ?: return@LaunchedEffect
        AppFeedbackBus.showSuccess("Ahora estas en $resolvedTargetLabel.")
        navController.navigate(route) {
            popUpTo(Screen.NotificationAccountBridge.route) { inclusive = true }
            launchSingleTop = true
        }
        viewModel.consumeCompletedRoute()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Abrir en otra cuenta",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Estas en $resolvedCurrentLabel, pero este contenido llego a $resolvedTargetLabel.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Cuenta activa",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = resolvedCurrentLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Cuenta destino",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = resolvedTargetLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!contextTitle.isNullOrBlank() || !preview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = contextTitle ?: "Actividad pendiente",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!preview.isNullOrBlank()) {
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (!hasStoredTarget) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "No encontramos una sesion guardada para $resolvedTargetLabel. Inicia sesion manualmente una vez con esa cuenta y luego podras abrir estas notificaciones sin perder el contexto.",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            if (hasStoredTarget) {
                Button(
                    onClick = {
                        viewModel.clearErrorMessage()
                        viewModel.continueWithAccount(
                            accountId = targetAccountId.orEmpty(),
                            destinationRoute = destinationRoute
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSwitching
                ) {
                    if (isSwitching) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(vertical = 2.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Continuar con $resolvedTargetLabel")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                Button(
                    onClick = {
                        navController.navigate(Screen.AddAccountLogin.route) {
                            launchSingleTop = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Agregar o iniciar sesion con $resolvedTargetLabel")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            OutlinedButton(
                onClick = {
                    navController.navigate(Screen.Home.createRoute(startTab = "messages")) {
                        popUpTo(navController.graph.findStartDestination().id)
                        launchSingleTop = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Quedarme en $resolvedCurrentLabel")
            }
        }
    }
}







