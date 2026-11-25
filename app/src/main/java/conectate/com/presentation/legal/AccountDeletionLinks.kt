package conectate.com.presentation.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

internal const val PUBLIC_PRIVACY_POLICY_URL = "https://conectate.tail9b267c.ts.net/privacidad"
internal const val PUBLIC_ACCOUNT_DELETION_URL = "https://conectate.tail9b267c.ts.net/eliminar-cuenta"

/** Opens the public request journey; opening a link never deletes an account. */
@Composable
internal fun AccountDeletionLinks(showPrivacyLink: Boolean = true) {
    val uriHandler = LocalUriHandler.current
    var openFailed by remember { mutableStateOf(false) }
    fun openOfficialPage(url: String) {
        openFailed = try {
            uriHandler.openUri(url)
            false
        } catch (_: Exception) {
            true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Solicita la eliminación de tu cuenta y sus datos en la web o escribe a administracion@novvinapp.com. No envíes contraseñas ni códigos de acceso.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { openOfficialPage(PUBLIC_ACCOUNT_DELETION_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Eliminar mi cuenta y mis datos") }
        if (showPrivacyLink) {
            TextButton(onClick = { openOfficialPage(PUBLIC_PRIVACY_POLICY_URL) }) {
                Text("Leer política de privacidad en la web")
            }
        }
        if (openFailed) {
            Text(
                "No se pudo abrir el navegador. Puedes solicitar ayuda y eliminación en administracion@novvinapp.com.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
