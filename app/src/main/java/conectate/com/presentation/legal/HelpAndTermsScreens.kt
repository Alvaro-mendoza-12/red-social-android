package conectate.com.presentation.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

private data class DocumentSection(val title: String, val body: String)

@Composable
fun HelpCenterScreen(navController: NavController) {
    val sections = listOf(
        DocumentSection("Cuenta y acceso", "Recupera el acceso, administra tus correos, contraseña, sesiones web y eliminación de cuenta desde Configuración."),
        DocumentSection("Mensajes pendientes", "Un reloj indica que el mensaje está guardado en el dispositivo. Se enviará automáticamente cuando regrese una conexión validada; no necesitas mantener el chat abierto."),
        DocumentSection("Monedas y compras", "Los precios los entrega Google Play. Una compra pendiente no acredita monedas. Conserva el identificador del pedido para solicitar ayuda, pero nunca compartas contraseñas ni datos completos de pago."),
        DocumentSection("Directos y regalos", "Solo se celebra un regalo después de que el servidor confirma el descuento. Si el saldo no alcanza, abre Recargar y completa la compra en Google Play antes de intentarlo nuevamente."),
        DocumentSection("Privacidad y seguridad", "Bloquea, silencia o reporta desde el contenido o perfil. Los reportes deben describir el hecho sin incluir información sensible innecesaria."),
        DocumentSection("Problemas técnicos", "Comprueba permisos de cámara y micrófono, conexión, ahorro de batería y versión instalada. Puedes contactar soporte desde la pantalla anterior.")
    )
    DocumentScreen(navController, "Centro de ayuda", sections)
}

@Composable
fun TermsAndConditionsScreen(navController: NavController) {
    val sections = listOf(
        DocumentSection("1. Uso del servicio", "Debes proporcionar datos reales, proteger tu cuenta y utilizar Novvin de manera legal y respetuosa."),
        DocumentSection("2. Contenido y convivencia", "Conservas la responsabilidad sobre lo que publicas o transmites. No se permite acoso, fraude, explotación, suplantación, amenazas ni contenido ilegal."),
        DocumentSection("3. Monedas y regalos", "Las monedas son bienes digitales consumibles, no dinero retirable. Los precios y cobros se procesan mediante la tienda aplicable. Los reembolsos pueden revertir las monedas o generar un ajuste de saldo."),
        DocumentSection("4. Ganancias de creadores", "Solo los regalos elegibles comprados con dinero real pueden generar ganancias. Se aplican verificación, retención, revisión antifraude, mínimos y condiciones del proveedor de pagos."),
        DocumentSection("5. Directos", "El anfitrión controla invitados y moderación. Los participantes deben respetar permisos, límites de edad, normas comunitarias y derechos de terceros."),
        DocumentSection("6. Disponibilidad", "Podemos limitar funciones por seguridad, región, dispositivo, mantenimiento, cumplimiento legal o abuso. Las operaciones económicas se registran de forma idempotente."),
        DocumentSection("7. Suspensión y apelación", "Las infracciones pueden producir reducción de alcance, retirada de contenido o suspensión. Cuando corresponda, podrás consultar el motivo y apelar."),
        DocumentSection("8. Cambios", "Los cambios materiales se comunicarán en la aplicación. Continuar usando el servicio después de su entrada en vigor supone aceptar la versión actualizada.")
    )
    DocumentScreen(navController, "Términos y condiciones", sections)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentScreen(navController: NavController, title: String, sections: List<DocumentSection>) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(sections) { section ->
                Card {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(section.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(section.body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
