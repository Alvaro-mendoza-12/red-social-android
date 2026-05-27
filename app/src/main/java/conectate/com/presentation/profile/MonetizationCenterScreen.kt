package conectate.com.presentation.profile

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import conectate.com.data.model.AdCampaign
import conectate.com.data.model.SubscriptionPlan
import conectate.com.data.model.UserSubscription
import conectate.com.data.repository.monthlyPriceInDiamonds
import conectate.com.presentation.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonetizationCenterScreen(
    navController: NavController,
    viewModel: MonetizationCenterViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dashboard = state.dashboard
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(state.message, state.error) {
        val message = state.message ?: state.error
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearTransientMessage()
        }
    }

    LaunchedEffect(dashboard.activeAds.map { it.id }.joinToString("|")) {
        dashboard.activeAds.forEach { ad ->
            viewModel.recordAdImpression(ad.id)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Monetización") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MonetizationSummaryCard(
                balanceDiamonds = state.balanceDiamonds,
                subscription = dashboard.currentSubscription
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Premium", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Activa funciones premium pagando con diamantes del wallet. La suscripción queda guardada en Supabase y vinculada a tu cuenta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (dashboard.plans.isEmpty() && !state.isLoading) {
                        Text("No hay planes activos por ahora.", style = MaterialTheme.typography.bodyMedium)
                    }

                    dashboard.plans.forEach { plan ->
                        PremiumPlanCard(
                            plan = plan,
                            currentSubscription = dashboard.currentSubscription,
                            balanceDiamonds = state.balanceDiamonds,
                            isBusy = state.isSubscribing || state.isLoading,
                            onSubscribe = { viewModel.subscribe(plan) }
                        )
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Anuncios activos con registro de impresiones real. Esto conecta el inventario de campañas con el feed y el programa de creadores.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (dashboard.activeAds.isEmpty() && !state.isLoading) {
                        Text("Sin campañas activas en este momento.", style = MaterialTheme.typography.bodyMedium)
                    }

                    dashboard.activeAds.forEach { ad ->
                        AdCampaignCard(
                            ad = ad,
                            onOpen = {
                                runCatching { uriHandler.openUri(ad.targetUrl) }
                            }
                        )
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Creadores", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Completa tu solicitud para monetizar con gifts, reparto de anuncios y pagos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { navController.navigate(Screen.CreatorProgram.route) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Abrir programa de creadores")
                    }
                }
            }

            if (state.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun MonetizationSummaryCard(
    balanceDiamonds: Int,
    subscription: UserSubscription?
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Centro de ingresos", style = MaterialTheme.typography.labelLarge)
            Text(
                "${balanceDiamonds.coerceAtLeast(0)} diamantes",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            val subscriptionLabel = subscription?.planName ?: subscription?.planId
            Text(
                text = if (subscriptionLabel.isNullOrBlank()) {
                    "Premium no activo"
                } else {
                    "Premium activo: $subscriptionLabel hasta ${formatDate(subscription?.currentPeriodEnd)}"
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PremiumPlanCard(
    plan: SubscriptionPlan,
    currentSubscription: UserSubscription?,
    balanceDiamonds: Int,
    isBusy: Boolean,
    onSubscribe: () -> Unit
) {
    val isCurrent = currentSubscription?.planId == plan.id
    val monthlyDiamonds = plan.monthlyPriceInDiamonds()
    val canAfford = balanceDiamonds >= monthlyDiamonds

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(plan.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("$monthlyDiamonds diamantes", style = MaterialTheme.typography.labelLarge)
            }
            if (!plan.description.isNullOrBlank()) {
                Text(plan.description, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Mensual: ${formatMoney(plan.priceMonthly)} | Anual: ${formatMoney(plan.priceYearly)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            plan.features.take(5).forEach { feature ->
                Text("- ${feature.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onSubscribe,
                enabled = !isBusy && !isCurrent && canAfford,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        isCurrent -> "Plan activo"
                        !canAfford -> "Saldo insuficiente"
                        else -> "Activar con wallet"
                    }
                )
            }
        }
    }
}

@Composable
private fun AdCampaignCard(
    ad: AdCampaign,
    onOpen: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ad.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Presupuesto: ${formatMoney(ad.budget)} | Gastado: ${formatMoney(ad.spent)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (ad.targetingHashtags.isNotEmpty()) {
                Text(
                    ad.targetingHashtags.joinToString(prefix = "#", separator = " #"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                ad.creativeUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Divider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Activo hasta ${formatDate(ad.endDate)}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onOpen) {
                    Text("Abrir")
                }
            }
        }
    }
}

private fun formatMoney(value: Double): String {
    return "$" + "%.2f".format(value.coerceAtLeast(0.0))
}

private fun formatDate(value: String?): String {
    return value?.substringBefore('T')?.takeIf { it.isNotBlank() } ?: "vigente"
}
