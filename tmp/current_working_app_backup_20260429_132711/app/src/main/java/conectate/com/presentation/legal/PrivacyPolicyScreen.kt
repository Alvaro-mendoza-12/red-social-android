package conectate.com.presentation.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(
    navController: NavController
) {
    val sections = listOf(
        "1. Información que recopilamos" to
            "Recopilamos datos de cuenta como correo, nombre de usuario y contenido que publicas. También registramos eventos técnicos para seguridad, moderación y estabilidad del servicio.",
        "2. Uso de la información" to
            "Usamos tu información para operar la plataforma, mostrar contenido, prevenir abuso, responder reportes y mejorar funciones como chat, videos y notificaciones.",
        "3. Seguridad y moderación" to
            "Podemos revisar contenido reportado para proteger a la comunidad. El incumplimiento de normas puede causar advertencias, eliminación de contenido o suspensión temporal de cuenta.",
        "4. Compartición de datos" to
            "No vendemos tu información personal. Solo compartimos datos con proveedores técnicos necesarios para operar el servicio (almacenamiento, notificaciones, analítica y seguridad).",
        "5. Retención y eliminación" to
            "Conservamos datos mientras la cuenta esté activa o cuando sea necesario para cumplir obligaciones legales, resolver disputas y prevenir fraude.",
        "6. Derechos del usuario" to
            "Puedes solicitar actualización o eliminación de datos desde soporte. Algunas acciones pueden requerir verificación de identidad y revisión de seguridad.",
        "7. Contenido permitido" to
            "No se permite contenido ilegal, violento extremo, explotación, acoso grave ni material sexual no consentido. Evita subir contenido inapropiado.",
        "8. Cambios en estas políticas" to
            "Estas políticas pueden actualizarse. Si hay cambios relevantes, se informará dentro de la app.",
        "9. Aceptación" to
            "Al crear cuenta o publicar contenido aceptas estas políticas y las normas de la comunidad."
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Políticas y normas") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Lee cuidadosamente estas políticas de privacidad y normas de la comunidad antes de usar Conéctate.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            items(sections) { section ->
                Column {
                    Text(
                        text = section.first,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = section.second,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Aceptar y volver")
                }
            }
        }
    }
}
