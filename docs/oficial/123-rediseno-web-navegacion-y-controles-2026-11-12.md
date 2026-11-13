# Rediseño web, navegación y acciones de Novvin

El propietario confirmó que el sticker «Muejeje», copiado de WhatsApp, vuelve a reproducirse. El correo `administracion@novvinapp.com` sirve para contacto directo. El remitente de los códigos de acceso y recuperación sigue con la configuración habitual. En esta revisión no se modificó ningún servicio de correo ni se generó otra APK.

## Qué cambió

- La portada pública muestra una conversación de demostración. Las entradas a mensajería, videos y Momentos identifican su contenido.
- En escritorio, la barra lateral identifica las cinco secciones principales y da acceso directo a búsqueda, notificaciones, salas de voz y exploración. En pantallas pequeñas se mantiene la navegación inferior. Los menús de sección llevan su nombre y restauran el foco al cerrarse con Escape.
- En los chats privados, grupos y comunidades, el clip abre los adjuntos, los tres puntos abren opciones y el lápiz de edición desemboca en un botón de confirmar. Los iconos y nombres distinguen enviar, guardar una corrección, grabar y detener. El menú privado tolera una respuesta que omite las preferencias del chat.
- En Social, la acción de añadir multimedia abre el selector de archivos y presenta el archivo escogido en el editor antes de publicar. El contador de reacciones muestra su valor, incluido cero. Compartir genera un enlace oficial y comunica si lo copió o si hubo un error. El control de publicidad sigue bajo el permiso de gestión del feed.
- Los enlaces de Ayuda y Perfil a una sección de Ajustes muestran la pestaña de destino. Las reacciones y favoritos de Videos exponen su estado activado. Ayuda incorpora un enlace de contacto directo.
- El menú «Ordenar y filtrar» de Videos estaba oculto por CSS en pantallas pequeñas. Se mantuvo junto al buscador y el menú de creación y se adaptó el ancho de sus opciones.

## Evidencia

Con el build de producción en Chrome y respuestas de prueba aisladas se recorrieron la portada, Social, la bandeja y una conversación, la búsqueda de mensajes, el menú, los adjuntos, el selector de fotos, la vista previa y el paso de Acceso y QR a Privacidad. Se comprobaron anchos de 320, 390, 1.100 y 1.440 px. También se abrieron las opciones y el selector de adjuntos en un grupo y en una comunidad, se siguió Editar hasta el perfil en Ajustes y se abrió el filtro de Videos en los cuatro anchos. Ninguno de esos recorridos desbordó horizontalmente el documento. En escritorio se comprobó también que Explorar permite entrar a Ayuda y volver a Social. El ensayo aislado no envió publicaciones ni mensajes a personas reales.

Pasaron 1.194 pruebas Jest en 187 suites y 123 pruebas Node. TypeScript, Vite y el control de las 21 páginas públicas finalizaron correctamente. ESLint conservó siete advertencias y cero errores. La revisión integral del móvil y la comprobación manual de todas las pantallas web siguen abiertas.

El control de nombres y símbolos sigue el criterio de [identificación coherente de WCAG](https://www.w3.org/WAI/WCAG22/Understanding/consistent-identification.html). El ensayo de Escape y retorno de foco sigue el [patrón de diálogo de WAI-ARIA](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/).

## Publicación

Vercel sirve el despliegue `dpl_HNXJDNcfmk4wK4Xe92Cx1Dzo36Nn` en https://novvinapp.com. Se verificó la portada con Chrome, su captura y el enlace a Mensajes. El dominio pasó 97 controles públicos después de la promoción final. Los recorridos anteriores se repitieron a 390 y 1.440 px con el HTML, los estilos y el JavaScript publicados y con respuestas de API de prueba. El historial de personas reales, el envío de correos y las compras quedaron fuera de ese ensayo.
