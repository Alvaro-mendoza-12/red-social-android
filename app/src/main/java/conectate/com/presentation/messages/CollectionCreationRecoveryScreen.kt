package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen

@Composable
internal fun CollectionCreationRecoveryScreen(
    name: String?, working: Boolean, error: String?, onContinue: () -> Unit, onBack: () -> Unit,
) {
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Creación pendiente", style = MaterialTheme.typography.headlineSmall)
            Text(if (name == null && working) "Estamos comprobando si quedó una creación pendiente."
                else if (name == null) "Vuelve a comprobar la creación pendiente antes de comenzar otra."
                else "Falta confirmar «$name». Continúa para recuperar o terminar su creación sin duplicarla.")
            if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = onContinue, enabled = !working) {
                Text(if (name == null) "Volver a comprobar" else "Continuar creación")
            }
            TextButton(onClick = onBack) { Text("Volver") }
        }
    }
}

/** Replace the completed form with the actual room, preserving the previous conversation list. */
internal fun NavController.openCreatedCollection(kind: String, id: String) {
    require(kind in setOf("group", "community") && id.isNotBlank())
    val form = if (kind == "group") Screen.CreateGroup.route else Screen.CreateCommunity.route
    val destination = if (kind == "group") Screen.GroupChat.createRoute(id) else Screen.CommunityChat.createRoute(id)
    navigate(destination) { popUpTo(form) { inclusive = true }; launchSingleTop = true }
}
