# Auditoría incremental de correcciones-dot — 10 de noviembre de 2026

## Alcance y estado

Esta tanda parte de `7f25192bad3eb9f2fd8297e84909e101886bfde3`. La autorización actual limita las publicaciones a `correcciones-dot`; no se actualizan las ramas estables ni se despliega en producción. Conserva las correcciones previas. Es una auditoría incremental, no el cierre integral de Android, web o backend.

## Defectos reproducidos y correcciones

- **P2, iconos del menú web:** Ayuda usaba Ajustes, reportes usaban Notificaciones, llamadas usaban cámara, personas cercanas usaban el globo de Social y otros destinos repetían símbolos ajenos. Se separó `SecondaryNavigation` y se asociaron once rutas con iconos explícitos; siete cambian. Se mantienen nombres accesibles, rutas y cierre al seleccionar. Commit `f5fe52312a5a783072316c3f5e702ad46aa02320`.
- **P3, sección activa incorrecta:** `primarySection` consideraba `/app/messages-other` y `/app/messagesarchive` parte de Mensajes. Se exige el límite de segmento, como en los demás apartados. Commit `3a6cfffc6f7cc9b665f8e52fb1e65b26b537d031`.
- **P1, resultados antiguos en solicitudes de amistad:** aceptar una solicitud y cambiar a Enviadas antes de su respuesta volvía a cargar Recibidas dentro de la pestaña Enviadas, incluso enlazando al perfil equivocado. Cambiar de cuenta conservaba el bloqueo de la acción anterior. Se invalidan los resultados de una vista anterior al cambiar de contexto o desmontar, se conserva el bloqueo de escritura de cada cuenta entre pestañas, se impide el envío duplicado de la acción vigente y se recarga solo la vista actual de la misma cuenta. Las pruebas controlan respuestas pendientes, cambios de pestaña y cuenta, una operación nueva y reintento tras error. Commit `11902007b225cb7b0f1b024e966680136fc63b87`. Esto valida el estado de la pantalla; no certifica por sí solo autorización del servidor.

## Evidencia local

- Regresión inicial de navegación: nueve fallos esperados antes de corregir (siete símbolos y dos rutas); después 34/34 comprobaciones focalizadas aprobadas.
- Regresión de amistad: fallos reproducidos de perfil incorrecto en Enviadas y botón bloqueado tras cambiar de cuenta; la revisión añadió ida/vuelta de pestañas y cuentas, el contrato legado Supabase, desmontaje y recarga de estado confirmado. Se conserva el bloqueo de escritura por cuenta entre vistas y se actualiza únicamente la vista vigente.
- Batería final después de la revisión: **183 suites Jest, 1.067 pruebas aprobadas**, incluidas nueve de solicitudes de amistad. Lint, build aislado y 106 pruebas Node se repitieron satisfactoriamente sobre este código.
- Primera batería completa: 181 suites Jest, 1.058 pruebas aprobadas.
- TypeScript `tsc -b`: aprobado.
- ESLint: cero errores, siete avisos de dependencias/limpieza de efectos en LIVE, Momentos y solicitudes. No se silencian ni se presentan como defectos demostrados por el aviso solo.
- `npm run build`: aprobado con `VITE_FALLBACK_API_URL=https://ci.novvin.invalid`, el origen aislado que ya usa el workflow Android. El candidato **no sirve para producción**. Puerta SEO: 19 páginas, JavaScript público cero, controles de metadatos, CSP, rutas, assets y privacidad aprobados.
- `npm run test:node`: 106/106 aprobadas después del build. El intento anterior sin `dist` encontró archivos ausentes; no se contabiliza como aprobado.
- La compilación sin variable de API falla explícitamente. No se añadió una dirección supuesta ni se alteró la configuración real.
- La instalación inicial de dependencias no pudo crear la caché de npm fuera del espacio de trabajo. Se completó con caché temporal explícita. No cambia el lockfile.

## Móvil e integración: cobertura limitada

Se leyeron los resolutores de notificaciones de conversación, retorno de llamada, enlaces de salas y enlaces de contenido compartido, y la prueba de destino de notificaciones. Se contrastaron tipos y destinos de navegación; no se identificó en esa lectura un defecto nuevo confirmado que justifique cambiar Android. No se afirma que Android esté libre de errores.

En este entorno no se encontró SDK, adb ni emulador Android. No se ejecutaron pruebas instrumentadas, pruebas JVM Android ni un build APK/AAB local. La evidencia histórica de las revisiones 101–113 sigue siendo histórica y no se reutiliza como aprobación de esta tanda.

## Prioridad siguiente y criterios observables

Estos son criterios técnicos de auditoría propuestos, no funciones nuevas aprobadas ni resultados ya comprobados:

1. **P1, identidad y permisos:** entrada, renovación y salida de cuenta; A→B con cargas y acciones pendientes. Ningún dato/error/acuse de A debe sobrescribir B. Comprobar también el servidor, no solo componentes simulados.
2. **P1, amistad y mensajería:** descubrir una persona, invitar, aceptar/rechazar, abrir conversación, enviar texto/archivo, perder y recuperar red. Conservar borradores, evitar duplicados y verificar destinatario y confirmación real en ambos clientes.
3. **P1, privacidad y moderación:** bloquear/reportar, contenido privado y enlaces compartidos. Verificar permisos con cuentas de prueba separadas y registrar denegación, no inferirla de ocultar botones.
4. **P2, publicación y reproducción:** crear/editar contenido, fallo de subida, reintento, regresar y cambiar pestaña. Conservar el trabajo y medir tiempos reales con red/dispositivo identificados. El editor web no acredita paridad completa con Android.
5. **P2, accesibilidad y apariencia:** comprobar iconos renderizados, foco, Escape/Tab, zoom, contraste, temas y anchos móviles/escritorio en navegador. Las pruebas DOM no sustituyen comprobación visual.
6. **P2, capacidad y mantenimiento:** investigar el chunk de más de 1 MB señalado por Vite antes de cambiar límites; medir transferencia y carga en lugar de prometer fluidez. Separar deuda de efectos React de errores funcionales reproducidos.

La web ya dispone de modo claro/oscuro/sistema, acentos y escala de texto (`core/webPreferences.ts`); el siguiente trabajo es verificar sus recorridos y usabilidad, no duplicar esas opciones. La audiencia inicial 18–30 años aún no define un nicho: no se justifica añadir funciones masivas sin demostrar que los recorridos básicos funcionan.

## Límites de entrega

No se usaron cuentas reales para enviar invitaciones/mensajes, hacer pagos ni modificar datos. El bloqueo es de esta instancia de pantalla: la idempotencia y las transiciones de estado atómicas del servidor frente a múltiples pestañas/dispositivos siguen pendientes de auditoría. No se cambió el servidor, no se publicó una web y no se generó APK nueva. Publicar commits en esta rama no equivale a desplegar ni aprobar CI. El estado de GitHub Actions debe consultarse para el SHA entregado.
