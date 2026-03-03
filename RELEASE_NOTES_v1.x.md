# Notas de la Versión - Actualización 2026.02.15

## 🎉 Nuevas Características

### Unificación Total de la Interfaz de Chat
- ✅ **Menú de Acciones Re-imaginado**: Se reemplazaron los diálogos antiguos por un `ModalBottomSheet` premium en Grupos y Comunidades, igualando la experiencia del chat 1-a-1.
- ✅ **Gestión Completa de Mensajes**:
  - **Edición**: Ahora puedes corregir mensajes enviados en cualquier tipo de chat.
  - **Eliminado Global**: Opción "Eliminar para todos" habilitada en Grupos y Comunidades.
  - **Respuestas e Hilos**: Soporte nativo para responder a mensajes específicos mediante gestos y acciones.
  - **Copiado de Texto**: Acceso rápido al portapapeles.
- ✅ **Barra de Entrada de Próxima Generación**:
  - Botón "+" para adjuntos con acceso a Imágenes, Videos y Audios.
  - Acceso directo a Cámara dentro del campo de texto.
  - Soporte mejorado para grabación y vista previa de mensajes de voz.

## 🔧 Correcciones y Estabilidad
- ✅ **Optimistic UI**: Envío de mensajes instantáneo sin "rueditas de carga" gracias a la sincronización en segundo plano con Supabase.
- ✅ **Real-Time en Retos**: Las respuestas y fotos de los retos ahora aparecen instantáneamente para todos los usuarios.
- ✅ **Adaptabilidad**: Corrección del scroll en el área de Retos para pantallas pequeñas.

---

# Notas de la Versión - Actualización 2026.01.10

## 🎉 Nuevas Características

### Sistema de Notificaciones Completo
- ✅ **Notificaciones en Tiempo Real**: Recibe notificaciones instantáneas cuando alguien:
  - Te sigue
  - Le da "Me gusta" a tus publicaciones
  - Le da "Me gusta" a tus videos
  - Comenta en tus posts o videos
  
- ✅ **Contador de Notificaciones**: Badge visual en el ícono de la campana que muestra cuántas notificaciones nuevas tienes
- ✅ **Pantalla de Notificaciones Mejorada**: 
  - Botón de "Recargar" para actualizar manualmente
  - Indicador visual de notificaciones no leídas
  - Navegación directa al contenido relacionado

## 🔧 Correcciones Importantes

### Sistema de Mensajería
- ✅ **Persistencia Mejorada**: Los mensajes ahora se guardan correctamente y no desaparecen al cambiar de chat
- ✅ **Sincronización Estable**: Orden correcto de mensajes (los más recientes primero)
- ✅ **Timestamps Confiables**: Fechas y horas de mensajes ahora son consistentes
- ✅ **Chats de Grupo**: Sincronización mejorada para grupos y comunidades

### Sistema de Perfiles
- ✅ **Badges Visibles**: Los badges de usuario ahora se muestran correctamente en los perfiles
- ✅ **Verificación**: Icono de verificado visible para usuarios verificados
- ✅ **Estadísticas Precisas**: Contadores de seguidores, seguidos y publicaciones actualizados correctamente

## 🐛 Bugs Corregidos

- ❌ Error 400/300 en notificaciones → **Resuelto**
- ❌ Mensajes que desaparecían → **Resuelto**
- ❌ Chats que saltaban de posición → **Resuelto**
- ❌ Timestamps incorrectos en mensajes → **Resuelto**
- ❌ Notificaciones duplicadas → **Resuelto**

## 🚀 Mejoras de Rendimiento

- Carga más rápida de mensajes (fetch limitado a 100 más recientes)
- Decodificación de notificaciones en thread secundario (menos lag en UI)
- Optimización de consultas a base de datos con índices y joins

## ⚠️ Cambios Importantes para Desarrolladores

### Base de Datos (Supabase)
- Se añadieron columnas a la tabla `notifications`: `target_type`, `content`, `extra_data`
- Se corrigieron Foreign Keys para permitir joins automáticos con `profiles`
- Triggers automáticos para notificaciones de follow, likes

### Código
- Eliminadas llamadas manuales redundantes a `sendNotification` (ahora manejadas por triggers SQL)
- Sanitización de modelos de datos (eliminado uso de `LocalDateTime.now()` y `Instant.now()`)
- Versión de Room Database incrementada a v18

## 📋 Requisitos

- **Android**: 7.0 (API 24) o superior
- **Conexión a Internet**: Requerida para sincronización en tiempo real
- **Permisos**: 
  - Internet
  - Notificaciones push (opcional, pero recomendado)
  - Almacenamiento (para caché de imágenes/videos)

## 🔐 Seguridad

- RLS (Row Level Security) activado en todas las tablas sensibles
- Autenticación mediante JWT tokens
- Validación de permisos en servidor (Supabase)

---

**Fecha de Lanzamiento**: 10 de Enero, 2026  
**Versión**: 1.x.x (incrementar según tu esquema de versionado)
