# Documentación: Unificación de Interfaz de Chat (Grupos y Comunidades)

Esta documentación detalla los cambios realizados para unificar la experiencia de usuario en los chats de Grupos y Comunidades, alineándola con el diseño premium de los chats individuales (1-a-1).

## 1. Cambios en la Interfaz de Usuario (UI)

Se ha rediseñado completamente la fila de entrada de mensajes para ofrecer una experiencia más moderna y funcional:

- **Botón de Adjuntos ("+"):** Se añadió un icono `Icons.Default.Add` a la izquierda del campo de texto. Al presionarlo, se despliega un `ModalBottomSheet` con opciones para compartir Imagen, Video y Audio.
- **Icono de Cámara Integrado:** Dentro del `TextField` (como `trailingIcon`), se incluyó un acceso rápido a la galería de imágenes para facilitar el envío de fotos.
- **Campo de Texto Adaptativo:**
    - Se limitó el crecimiento vertical a un máximo de 4 líneas antes de habilitar el desplazamiento interno.
    - Se agregaron esquinas redondeadas (`CircleShape`) y un estilo visual coherente con el lenguaje de diseño del sistema.
- **Acciones Dinámicas del FAB:** El botón flotante principal cambia su icono y función según el estado:
    - **Enviar:** Si el usuario ha escrito texto.
    - **Micrófono:** Si el campo está vacío, permitiendo iniciar una grabación de voz.
    - **Indicador de Carga:** Muestra un progreso circular si hay un envío en curso.

## 2. Sistema de Adjuntos y Multimedia

Se implementó una lógica modular para el manejo de archivos:

- **Launchers Específicos:** Se configuraron `ActivityResultLauncher` independientes para cada tipo de contenido (`image/*`, `video/*`, `audio/*`).
- **Hoja de Opciones:** Un panel inferior estilizado con iconos de colores (`Image`, `VideoLibrary`, `Audiotrack`) y tipografía robusta.

## 3. Grabación de Voz y Control de Estados

Se refinó el flujo de grabación de mensajes de voz:

- **Feedback Visual:** Durante la grabación, el botón FAB cambia a color **Rojo** y el campo de texto se deshabilita para evitar conflictos de input.
- **Controles de Grabación:** Se incluyen botones para pausar, cancelar, reproducir la vista previa y enviar la grabación final.

## 4. Control de Acceso y Restricciones

Se mejoró la representación visual de las reglas de negocio:

- **Solo Administradores:** Si la opción "Solo los administradores pueden escribir" está activa, el área de entrada se reemplaza por una barra con un **icono de candado** y un mensaje informativo claro.
- **Gestión de Membresía:** Se integraron de forma fluida los mensajes informativos para usuarios que han sido expulsados o ya no forman parte de un grupo/comunidad, incluyendo opciones para eliminar el chat localmente.

## 5. Correcciones Técnicas y Estabilidad

- **Ciclo de Vida:** Se corrigió un problema donde el chat se quedaba cargando indefinidamente al añadir llamadas a `viewModel.init(id)` mediante `LaunchedEffect`.
- **Estructura de Código:** Se realizó una limpieza profunda de las clases `GroupChatScreen.kt` y `CommunityChatScreen.kt`, corrigiendo anidamientos de llaves incorrectos y optimizando el manejo de estados de Compose.
- **Compilación:** Se verificó la integridad del proyecto, asegurando que todos los tipos de datos (como el mapeo entre `GroupMessage`/`CommunityMessage` a `Message` genérico para la burbuja) funcionen correctamente.

## 6. Unificación de Acciones de Mensaje (Edit, Delete, Copy, Reply)

Se ha estandarizado el menú de acciones al mantener presionado un mensaje en todas las plataformas:

- **Transición a BottomSheet:** Se eliminaron los antiguos `AlertDialog` en Grupos y Comunidades, reemplazándolos por un `ModalBottomSheet` que ofrece una experiencia más fluida y nativa, idéntica al chat individual.
- **Funcionalidad Completa (Propia y Ajena):**
    - **Editar:** Disponible para mensajes de texto propios.
    - **Eliminar para todos:** Ahora funcional en Grupos y Comunidades para mensajes propios.
    - **Eliminar para mí:** Opción de limpieza local.
    - **Copiar y Responder:** Accesibles de forma intuitiva para cualquier mensaje (siempre que no esté eliminado).
- **Consistencia Visual:** Se unificaron los iconos y colores (ej. Rojo para "Eliminar para todos") en toda la aplicación.

---
**Fecha:** 15 de Febrero de 2026
**Estado:** Implementado, Refactorizado y Verificado para Producción.
**Archivos Afectados:**
- `ChatScreen.kt`
- `GroupChatScreen.kt`
- `CommunityChatScreen.kt`
- `GroupChatViewModel.kt`
- `CommunityChatViewModel.kt`
- `ChatViewModel.kt`
