# Documentación de Estado Actual: Grupos y Comunidades
Fecha: 2026-02-12
Estado: Fase 4 - Pre-Mejora Avanzada

## Grupos Actuales (1:1)
- **Creación**: El creador se establece como 'admin', otros como 'member'.
- **Miembros**: Solo creadores/admins pueden añadir miembros (actualmente).
- **Mensajes**: Soporta Texto y Audio. Los modelos tienen `media_url` pero la UI no permite subir fotos/videos directamente.
- **Roles**: 'admin', 'member'. No hay delegación de roles post-creación.
- **Eliminación**: Soft-delete para creadores/admins. Los miembros normales solo salen del grupo localmente.

## Comunidades Actuales
- **Privacidad**: Soporta Públicas y Privadas.
- **Miembros**: Cualquiera puede unirse a una pública.
- **Mensajes**: Soporta Texto y Audio. Todos pueden hablar.
- **Roles**: 'admin', 'member'.
- **Eliminación**: Similar a grupos.

## Próximos Cambios (Solicitados por USER)
1. **Grupos**:
   - Cualquier miembro puede añadir gente.
   - Delegación de Admin (Admin -> Admin).
   - Solo Admins pueden eliminar gente (pero no al creador original).
   - Edición de Info del grupo (Foto, Nombre, Desc).
   - Enviar Fotos y Videos.

2. **Comunidades**:
   - Edición de Info (Creador solo).
   - Configuración "Quién puede hablar" (Todos vs Solo Administrador/Creador).
   - Enviar Fotos y Videos.

## Archivos Críticos a Proteger
- `GroupRepository.kt`
- `CommunityRepository.kt`
- `GroupDetailViewModel.kt`
- `GroupChatViewModel.kt`
- `CommunityDetailViewModel.kt`
- `CommunityChatViewModel.kt`

---
*He realizado esta documentación como respaldo antes de iniciar las modificaciones profundas.*
