package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CollectionCreationSessionNotice(onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("La sesión cambió. Vuelve a abrir este formulario para continuar.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Volver") }
        }
    }
}
