# Guía del Sistema de Mensajería (Directos, Grupos y Comunidades)

Este documento detalla la arquitectura, el flujo de datos y las reglas de negocio del sistema de mensajería implementado en la aplicación.

## 🏗️ Arquitectura General

El sistema utiliza un enfoque de **Offline-First** con sincronización en tiempo real:
- **Local Cache**: Room Database (`ChatDao`, `GroupDao`, `CommunityDao`).
- **Remoto**: Supabase (Postgrest para API REST y Realtime para eventos en vivo).
- **Transporte**: Ktor HTTP Client (Configurado en `SupabaseConfig`).

---

## 🔍 Búsqueda de Usuarios (UserRepository)

La búsqueda ha sido optimizada para ser robusta contra caracteres especiales y eficiente en el filtrado:
- **Tecnología**: Utiliza el cliente Postgrest de Supabase en lugar de llamadas HTTP manuales.
- **Campos de Búsqueda**: Busca coincidencias parciales (case-insensitive) en `username`, `display_name` y `full_name`.
- **IDs Numéricos**: Soporta búsqueda exacta por `numeric_id`.
- **Flujo**:
  1. El usuario escribe en `SearchUsersScreen`.
  2. `UserRepository.searchUsers(query)` sanitiza el input y ejecuta una consulta con filtro `or`.
  3. Al seleccionar un usuario, se navega directamente a la pantalla de Chat para agilizar el flujo.

---

## 👥 Grupos (GroupRepository)

Los grupos permiten comunicación multi-usuario con persistencia local inmediata.

### Flujo de Creación
1. **Interfaz**: El usuario selecciona miembros y define nombre/imagen en `CreateGroupScreen`.
2. **Optimismo**: Se realiza una inserción local inmediata en Room para que el creador vea el grupo al instante.
3. **Backend**: Se inserta el grupo y sus miembros en Supabase.
4. **Resiliencia**: Se permiten nombres duplicados para máxima flexibilidad del usuario.

### Listado y Orden
- Los grupos se ordenan dinámicamente: `ORDER BY COALESCE(last_message_at, created_at) DESC`.
- Esto garantiza que los grupos nuevos (sin mensajes) aparezcan arriba del todo basándose en su fecha de creación.

---

## 🏘️ Comunidades (CommunityRepository)

Las comunidades son espacios públicos o privados con lógica de descubrimiento y notificaciones selectivas.

### Descubrimiento y Membresía
- **Públicas**: Visibles para todos en la pestaña de Comunidad.
- **Membresía**: Los usuarios pueden unirse para recibir actualizaciones.

### 🔔 Notificaciones e hilos (Lógica Crítica)
- **Filtrado por Membresía**: El sistema verifica si el usuario es miembro (`isUserMemberSync`) antes de procesar un mensaje entrante.
- **Comportamiento**:
  - **Miembro**: Recibe notificaciones push y el contador de mensajes no leídos (`unread_count`) se incrementa.
  - **No Miembro**: El mensaje se guarda en la base de datos local (para historial futuro), pero **NO** se muestra notificación ni se incrementa el contador.
- **Auto-Lectura**: Si el usuario tiene el chat abierto, el contador no se incrementa y el mensaje se marca como leído automáticamente.

---

## 💾 Estándares de Datos

1. **Timestamps**: Se utiliza ISO 8601 (`java.time.Instant.now().toString()`) en todos los repositorios para garantizar compatibilidad total con los tipos `timestamptz` de Supabase.
2. **IDs**: UUIDs en minúsculas generados en el cliente para evitar colisiones.
3. **Sanitización**: Los IDs se limpian con `trim().lowercase()` antes de cualquier operación para evitar desajustes por espacios o mayúsculas.

---

## 🚀 Verificación de Estado
- **Compilación**: El sistema está verificado para compilar sin errores de tipos o nulos.
- **Realtime**: Las suscripciones se inician automáticamente al autenticar al usuario y se reinician si se pierde la conexión.
