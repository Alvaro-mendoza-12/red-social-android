# Documentación Técnica de Cambios y Soluciones (Feb 2026)

Este documento detalla los problemas críticos resueltos y las mejoras implementadas en el sistema de Mensajería y Retos (Challenges) de la aplicación Conéctate.

## 1. Problema: Mensajería Lenta ("Ruedita Cargando")
**Síntoma:** Al enviar mensajes en Grupos o Comunidades, la UI se bloqueaba mostrando un indicador de carga hasta que el servidor respondía.
**Causa:** La operación de envío a Supabase se hacía en el hilo principal o bloqueaba la coroutina de UI hasta recibir confirmación.

**Solución Implementada (Optimistic UI):**
- **Archivo:** `GroupRepository.kt`, `CommunityRepository.kt`
- **Lógica:**
    1. El mensaje se guarda inmediatamente en la base de datos local (Room).
    2. La UI se actualiza al instante gracias al `Flow` local.
    3. El envío a Supabase se realiza en segundo plano (`scope.launch`) sin bloquear al usuario.
- **Resultado:** Envío instantáneo percibido por el usuario.

## 2. Problema: Retos - Lista Vacía y Sin Real-Time
**Síntoma:** Al subir una respuesta a un reto, la lista aparecía vacía. Además, los usuarios no veían las respuestas de otros participantes a menos que reiniciaran la app.
**Causas:**
- **Frontend:** La suscripción a Realtime (`observeSubmissions`) no estaba capturando todos los eventos o reutilizaba canales cerrados.
- **Backend:** La tabla `challenge_submissions` no tenía activada la publicación en `supabase_realtime` o las políticas RLS restringían la visibilidad.

**Soluciones Implementadas:**
- **Frontend (`ChallengeRepository.kt`):**
    - Se reescribió `observeSubmissions` para usar un canal único por sesión (`System.currentTimeMillis()`).
    - Se configuró para escuchar `INSERT`, `UPDATE` y `DELETE` de forma robusta.
    - Se implementó un mecanismo de recarga completa tras cada evento para asegurar consistencia.
- **Frontend (`ChallengeViewModel.kt`):**
    - Se añadió `observeSubmissions` inmediatamente después de un envío exitoso para poblar la lista local.
    - Se creó `refreshChallengeData()` para permitir recarga manual vía "Pull-to-Refresh".
- **Frontend (`SocialScreen.kt`):**
    - Se integró la recarga de retos en el gesto de "deslizar hacia abajo".
- **Backend (SQL):**
    - Se ejecutó `ALTER PUBLICATION supabase_realtime ADD TABLE challenge_submissions` para activar eventos.
    - Se ajustaron las políticas de seguridad (RLS) para permitir lectura pública a usuarios autenticados.

## 3. Problema: UI de Retos en Pantallas Pequeñas
**Síntoma:** El botón "Aceptar Reto" no era visible en dispositivos pequeños y no se podía hacer scroll.
**Solución:**
- **Archivo:** `SocialScreen.kt`
- **Cambio:** Se añadió `Modifier.verticalScroll(rememberScrollState())` al componente `LockedRetoUI`, permitiendo el desplazamiento vertical.

## 4. Estado Actual del Sistema
- **Mensajería:** Rápida, no bloqueante y robusta ante fallos de red momentáneos.
- **Retos:** Completamente en tiempo real. Cuando un usuario sube una foto/video, aparece automáticamente en las pantallas de los demás sin recargar.
- **UI:** Adaptable a pantallas pequeñas.

## 5. Recomendaciones Finales
- Mantener el script SQL `FIX_REALTIME_RETOS.sql` como referencia de la configuración necesaria en Supabase.
- Si se añaden nuevas tablas con funcionalidad en tiempo real, recordar ejecutar `ALTER PUBLICATION...` para ellas.
