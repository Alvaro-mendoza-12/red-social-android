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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
            "Tratamos el correo, nombre, credenciales protegidas, país, fecha de nacimiento, género opcional, avatar —que puede ser imagen o video breve—, portada, biografía, intereses, insignias, preferencias y enlaces sociales que decidas publicar. La fecha de nacimiento permite aplicar protecciones por edad y, si lo autorizas, avisos de cumpleaños.",
        "2. Contenido y comunicaciones" to
            "Procesamos publicaciones, fotografías, videos, audios, documentos, comentarios, reacciones, mensajes, grupos, comunidades, Momentos y sus audiencias, destacados, llamadas, directos, batallas, invitados, salas de voz, Rondas Novvin y actividad de PlayMatch. Los mensajes privados se tratan para entregar y mantener las funciones solicitadas, sincronizar dispositivos y aplicar controles de seguridad cuando corresponda.",
        "3. Archivos, cámara, micrófono y pantalla" to
            "La app solicita acceso cuando eliges una función que lo necesita: seleccionar multimedia, crear contenido, usar un avatar de video, grabar audio, llamar, transmitir, añadir música al audio de un LIVE o compartir pantalla. La captura de pantalla exige la confirmación del sistema y se detiene al salir.",
        "4. Ubicación, radar y contactos" to
            "La ubicación se usa con tu permiso para el radar de personas cercanas, contenido geográfico o para compartir una ubicación. Los contactos se leen únicamente cuando decides seleccionar o compartir uno. Puedes retirar estos permisos desde los ajustes de Android.",
        "5. Personalización y recomendaciones" to
            "Usamos intereses, interacciones, contenido visto, tiempo de reproducción, cuentas seguidas, elementos guardados, juego seleccionado y preferencias para ordenar feeds, recomendar contenido o jugadores disponibles y personalizar la experiencia. Puedes modificar temas, seguridad de contenido, elementos ocultos y notificaciones.",
        "6. Seguridad, moderación y decisiones automatizadas" to
            "Registramos eventos técnicos, sesiones, reportes, bloqueos y señales de abuso para prevenir fraude, resolver fallos y proteger la comunidad. La moderación automática puede detectar spam, insultos alterados, contenido sensible o para adultos y aplicar advertencias, ocultamiento o revisión. Puedes reportar contenido o usuarios y solicitar revisión de medidas sensibles.",
        "7. Notificaciones y actividad social" to
            "Podemos enviarte notificaciones de mensajes, llamadas, solicitudes, publicaciones, directos, salas y cumpleaños de amigos según tus ajustes. Puedes desactivar cada categoría desde Configuración o desde Android.",
        "8. Infraestructura y proveedores" to
            "No vendemos tu información personal. La operación principal utiliza servidores propios administrados por Novvin, incluida infraestructura Ubuntu para API, base de datos y multimedia. También podemos usar proveedores necesarios de notificaciones, conectividad, almacenamiento o seguridad; reciben únicamente los datos necesarios para su función. El país de tratamiento puede diferir del tuyo y se aplicarán las garantías exigibles.",
        "9. Copias elegidas por el usuario" to
            "Desde Configuración puedes exportar o restaurar un archivo de preferencias mediante el selector seguro de Android y elegir un proveedor disponible, como Google Drive. La copia contiene tema, accesibilidad, notificaciones, chat y seguridad; no incluye contraseñas, sesiones, mensajes ni multimedia. Novvin no recibe las credenciales del proveedor elegido y tú controlas y eliminas ese archivo desde dicho proveedor.",
        "10. Retención, acceso y eliminación" to
            "Conservamos los datos mientras la cuenta esté activa o durante el tiempo necesario para seguridad, obligaciones legales y resolución de disputas. Puedes actualizar tus datos y solicitar desde la app la eliminación de la cuenta y sus datos asociados; solo se conservará lo estrictamente necesario por ley, seguridad o prevención de fraude, con la finalidad y el plazo aplicables.",
        "11. Usuarios menores y fecha real" to
            "Debes indicar tu fecha de nacimiento real. Novvin no cambia ni falsifica la edad declarada. Cuando la legislación local requiera autorización o protecciones adicionales para menores, se aplicarán los controles correspondientes sin ocultar la edad proporcionada.",
        "12. Normas de la comunidad" to
            "No se permite contenido ilegal, explotación, amenazas, acoso grave, suplantación, fraude, violencia extrema, material sexual no consentido ni vulneraciones reiteradas de derechos. Podemos limitar alcance, retirar contenido, suspender cuentas y recibir apelaciones.",
        "13. Cambios y contacto" to
            "Estas políticas se actualizaron el 13 de agosto de 2026 para reflejar las funciones actuales de Novvin. Los cambios importantes se comunicarán dentro de la aplicación. Las solicitudes de privacidad, acceso, corrección, eliminación o soporte pueden realizarse por los canales oficiales indicados en la app.",
        "14. Aceptación" to
            "Al pulsar “Aceptar y volver” confirmas que leíste estas políticas y las normas de la comunidad. Puedes volver al formulario sin perder los datos que ya escribiste."
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Políticas y normas") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
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
