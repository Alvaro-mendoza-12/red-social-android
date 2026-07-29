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
        "1. Datos de cuenta y perfil" to
            "Tratamos el correo, nombre, contraseña protegida, país, fecha de nacimiento, género opcional, fotografía, biografía, intereses y preferencias que proporciones. La fecha de nacimiento permite personalizar la experiencia y generar avisos de cumpleaños para tus amigos.",
        "2. Contenido y comunicaciones" to
            "Procesamos publicaciones, fotografías, videos, audios, documentos, comentarios, reacciones, mensajes, grupos, comunidades, llamadas, directos, salas de voz y Rondas Novvin. Los mensajes privados solo se usan para entregar y mantener las funciones solicitadas, además de controles de seguridad y reportes aplicables.",
        "3. Archivos, cámara y micrófono" to
            "La app solicita acceso únicamente cuando eliges una función que lo necesita, por ejemplo seleccionar multimedia, tomar una fotografía, grabar audio, llamar, transmitir o compartir pantalla. Los videos sin conexión se almacenan localmente en tu dispositivo y puedes eliminarlos desde Configuración.",
        "4. Ubicación, radar y contactos" to
            "La ubicación se usa con tu permiso para el radar de personas cercanas, contenido geográfico o para compartir una ubicación. Los contactos se leen únicamente cuando decides seleccionar o compartir uno. Puedes retirar estos permisos desde los ajustes de Android.",
        "5. Personalización y recomendaciones" to
            "Usamos intereses, interacciones, contenido visto, cuentas seguidas, elementos guardados y preferencias para ordenar los feeds, recomendar contenido y personalizar la experiencia. Puedes modificar preferencias, temas, contenido oculto y configuraciones de notificación.",
        "6. Seguridad, moderación e inteligencia artificial" to
            "Registramos eventos técnicos, sesiones, reportes y señales de abuso para prevenir fraude, resolver fallos y proteger la comunidad. Los sistemas automáticos o de inteligencia artificial pueden ayudar a detectar spam, contenido dañino o marcas de agua; las decisiones sensibles pueden revisarse y apelarse.",
        "7. Notificaciones y actividad social" to
            "Podemos enviarte notificaciones de mensajes, llamadas, solicitudes, publicaciones, directos, salas y cumpleaños de amigos según tus ajustes. Puedes desactivar cada categoría desde Configuración o desde Android.",
        "8. Proveedores y transferencia de datos" to
            "No vendemos tu información personal. Utilizamos proveedores necesarios para almacenamiento, entrega multimedia, notificaciones, seguridad y operación del servicio. Solo reciben los datos necesarios para prestar su función y deben aplicar medidas de protección.",
        "9. Retención, descarga y eliminación" to
            "Conservamos los datos mientras la cuenta esté activa o durante el tiempo necesario para seguridad, obligaciones legales y resolución de disputas. Puedes actualizar tus datos y solicitar la eliminación de la cuenta; la eliminación incluye los datos asociados salvo la retención estrictamente exigida por ley o seguridad.",
        "10. Usuarios menores y fecha real" to
            "Debes indicar tu fecha de nacimiento real. Novvin no cambia ni falsifica la edad declarada. Cuando la legislación local requiera autorización o protecciones adicionales para menores, se aplicarán los controles correspondientes sin ocultar la edad proporcionada.",
        "11. Normas de la comunidad" to
            "No se permite contenido ilegal, explotación, amenazas, acoso grave, suplantación, fraude, violencia extrema, material sexual no consentido ni vulneraciones reiteradas de derechos. Podemos limitar alcance, retirar contenido, suspender cuentas y recibir apelaciones.",
        "12. Cambios y contacto" to
            "Estas políticas se actualizaron el 29 de julio de 2026 para reflejar las funciones actuales de Novvin. Los cambios importantes se comunicarán dentro de la aplicación. Las solicitudes de privacidad y soporte pueden realizarse por los canales oficiales indicados en la app.",
        "13. Aceptación" to
            "Al pulsar “Aceptar y volver” confirmas que leíste estas políticas y las normas de la comunidad. Puedes volver al formulario sin perder los datos que ya escribiste."
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
                    text = "Lee cuidadosamente estas políticas de privacidad y normas de la comunidad antes de usar Novvin.",
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
                    onClick = {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("registration_privacy_accepted", true)
                        navController.popBackStack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Aceptar y volver")
                }
            }
        }
    }
}
