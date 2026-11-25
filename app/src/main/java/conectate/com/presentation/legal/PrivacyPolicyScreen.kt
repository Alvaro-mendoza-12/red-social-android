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
        "Responsable del tratamiento" to
            "Alvaro Mendoza Marín, persona natural domiciliada en Perú, es el responsable del tratamiento de tus datos personales en Novvin. Para consultas y solicitudes de privacidad, escribe a administracion@novvinapp.com.",
        "1. Datos de cuenta y perfil" to
            "Tratamos el correo, nombre, credenciales protegidas, país, fecha de nacimiento, género opcional, avatar —que puede ser imagen o video breve—, portada, biografía, intereses, insignias, preferencias y enlaces sociales que decidas publicar. La fecha de nacimiento permite aplicar protecciones por edad y, si lo autorizas, avisos de cumpleaños.",
        "2. Contenido y comunicaciones" to
            "Procesamos publicaciones, fotografías, videos, audios, documentos, comentarios, reacciones, mensajes, grupos, comunidades, Momentos y sus audiencias, destacados, llamadas, directos, batallas, invitados, salas de voz, Rondas Novvin y actividad de PlayMatch. Los mensajes privados se tratan para entregar y mantener las funciones solicitadas, sincronizar dispositivos y aplicar controles de seguridad cuando corresponda.",
        "3. Archivos, cámara, micrófono y pantalla" to
            "La app solicita acceso cuando eliges una función que lo necesita: seleccionar multimedia, crear contenido, usar un avatar de video, grabar audio, llamar, transmitir, añadir música al audio de un LIVE o compartir pantalla. La captura de pantalla exige la confirmación del sistema y se detiene al salir.",
        "4. Ubicación, radar y contactos" to
            "La ubicación se usa con tu permiso para el radar de personas cercanas, contenido geográfico o para compartir una ubicación. Los contactos se leen únicamente cuando decides seleccionar o compartir uno. Puedes retirar estos permisos desde los ajustes de Android.",
        "5. Personalización y recomendaciones" to
            "Usamos intereses, interacciones, contenido visto, tiempo de reproducción, cuentas seguidas, elementos guardados, juego seleccionado y preferencias para ordenar feeds, recomendar contenido o jugadores disponibles y personalizar la experiencia. Si eliges una búsqueda sugerida en un video, enviamos el tema seleccionado para buscar contenido. El historial de búsqueda general se guarda en el dispositivo y puedes borrarlo desde el buscador. Puedes modificar temas, seguridad de contenido, elementos ocultos y notificaciones.",
        "6. Seguridad, moderación y decisiones automatizadas" to
            "Registramos eventos técnicos, sesiones, reportes, bloqueos y señales de abuso para prevenir fraude, resolver fallos y proteger la comunidad. La moderación automática puede detectar spam, insultos alterados, contenido sensible o para adultos y aplicar advertencias, ocultamiento o revisión. Puedes reportar contenido o usuarios y solicitar revisión de medidas sensibles.",
        "7. Notificaciones y actividad social" to
            "Podemos enviarte notificaciones de mensajes, llamadas, solicitudes, publicaciones, directos, salas y cumpleaños de amigos según tus ajustes. Puedes desactivar cada categoría desde Configuración o desde Android.",
        "8. Infraestructura y proveedores" to
            "No vendemos tu información personal. La operación principal utiliza servidores propios para API, base de datos y multimedia. La app integra Firebase Cloud Messaging para notificaciones y Firebase Crashlytics para diagnóstico de cierres; pueden tratar identificadores de instalación, tokens, datos técnicos del dispositivo y registros de fallos. Google Play procesa las compras y Novvin recibe los datos necesarios para verificarlas y acreditar el producto. El sitio web utiliza Vercel y Cloudflare interviene en conectividad o almacenamiento. Los datos se transmiten mediante conexiones cifradas. Los proveedores reciben los datos necesarios para sus servicios y el país de tratamiento puede diferir del tuyo.",
        "9. Copias elegidas por el usuario" to
            "Desde Configuración puedes exportar o restaurar un archivo de preferencias mediante el selector seguro de Android y elegir un proveedor disponible, como Google Drive. La copia contiene tema, accesibilidad, notificaciones, chat y seguridad; no incluye contraseñas, sesiones, mensajes ni multimedia. Novvin no recibe las credenciales del proveedor elegido y tú controlas y eliminas ese archivo desde dicho proveedor.",
        "Cobros de creadores" to
            "Si participas en funciones de creador, tratamos la referencia del método de cobro que facilites, el país y la moneda, los saldos y el estado de las solicitudes de pago para administrar y verificar esas operaciones.",
        "10. Retención, acceso y eliminación" to
            "Conservamos los datos mientras la cuenta esté activa o sean necesarios para las funciones solicitadas. Puedes solicitar la eliminación desde el botón Eliminar mi cuenta y mis datos de esta pantalla o por correo a administracion@novvinapp.com, sin reinstalar la app. Verificamos la titularidad antes de eliminar la cuenta y los datos asociados del servicio activo. Pueden conservarse registros necesarios por ley, seguridad o prevención de fraude, identificadores mínimos que impidan restaurar cuentas mediante importaciones antiguas y copias de respaldo con tratamiento separado. Al atender tu solicitud se te indicarán las excepciones, motivos y plazos aplicables. Cerrar sesión o desinstalar no elimina la cuenta.",
        "Eliminar contenido publicado" to
            "Puedes retirar tus publicaciones, videos, Momentos y comentarios desde las opciones del contenido. Si el contenido sigue accesible después de eliminarlo, o necesitas retirar un dato concreto sin cerrar tu cuenta, escribe a administracion@novvinapp.com e identifica el contenido con su enlace o una descripción. Las copias descargadas o compartidas fuera de Novvin quedan bajo el control de quien las guardó.",
        "11. Usuarios menores y fecha real" to
            "La edad mínima para registrarse es de 13 años. Debes indicar tu fecha de nacimiento real; Novvin no cambia ni falsifica la edad declarada. Si tu país exige una edad superior o autorización adicional, debes cumplir esos requisitos. Las funciones con límites de edad adicionales mantienen sus propias restricciones.",
        "12. Normas de la comunidad" to
            "No se permite contenido ilegal, explotación, amenazas, acoso grave, suplantación, fraude, violencia extrema, material sexual no consentido ni vulneraciones de derechos. Publica música, imágenes y videos que hayas creado o que tengas derecho a usar. Si eres titular de derechos y solicitas la retirada de contenido, envía a administracion@novvinapp.com el enlace, la obra afectada y tu relación con ella. Podemos limitar el alcance, retirar contenido, suspender cuentas y recibir apelaciones.",
        "Seguridad infantil" to
            "Novvin prohíbe el abuso y la explotación sexual de menores, incluido el material de abuso sexual infantil, la captación con fines sexuales y la extorsión sexual. Usa Reportar en el contenido o perfil correspondiente, o escribe a administracion@novvinapp.com con un enlace o nombre de usuario para identificarlo. Evita descargar, reenviar o adjuntar el material.",
        "13. Cambios y contacto" to
            "Esta política corresponde a Novvin (conectate.com), con sitio oficial novvinapp.com, y se actualizó el 19 de noviembre de 2026. En administracion@novvinapp.com puedes solicitar acceso, actualización, corrección o supresión de tus datos, conocer su uso o pedir la revocación de la autorización cuando corresponda. Los cambios importantes se comunicarán dentro de la aplicación.",
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
            item { AccountDeletionLinks() }
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
