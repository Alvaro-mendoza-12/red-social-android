# Novvin — cierre técnico del 22 de julio de 2026

## Cambios y causas

- **Llamadas y salas:** los transceptores de video no existían desde el inicio,
  el cambio cámara/pantalla intentaba renegociar en estados inestables y el
  audio se enrutaba siempre al altavoz. Se dejaron transceptores `SEND_RECV`,
  reemplazo de pista sin renegociación, recuperación de ofertas atascadas,
  selección auricular/altavoz y una sola fuente de tono.
- **Cámara/pantalla y reacciones:** se completó la publicación de pista en sala,
  se retiró el overlay interno de SDP/rol/usuario y se suavizó la duración de
  reacciones.
- **Administración:** el botón de suspendidos era visualmente inerte y podía
  operar con un resumen obsoleto. Ahora abre confirmación, vuelve a consultar al
  servidor, rechaza administradores, exige suspensión previa y ejecuta el borrado
  completo solo con motivo y texto exacto.
- **Videos y modo sin conexión:** se unificó la clave de caché, aumentó el buffer
  útil, se conservan metadatos locales y una falla de red ya no se presenta como
  “no hay publicaciones”.
- **Radar:** se normalizaron los valores incompatibles `male/female` y
  `man/woman`, se limitaron los filtros visibles a Hombre/Mujer y se añadió
  barrido animado con avatares y actualización de presencia.
- **Editor multimedia:** cada trazo conserva su color, texto/stickers se pueden
  mover, hay proporciones de recorte y las fotos individuales admiten “ver una
  vez”. Registro, avatar, portada, publicaciones y momentos comparten un
  recortador que no sobrescribe el archivo original.
- **Marca:** el nombre visible en Android y web pasó a **Novvin**, incluidos
  splash, PWA, notificaciones, descargas, enlaces compartidos y marca de agua.
  Se conservaron `applicationId`, paquetes, `conectate://`, dominios, claves de
  caché y valores de base de datos para no romper actualizaciones ni sesiones.

## Archivos principales

- WebRTC/audio: `WebRTCManager.kt`, `CallRepository.kt`, `CallScreen.kt`,
  `CallViewModel.kt`, `VoiceRoomRepository.kt`, `VoiceRoomActiveScreen.kt`,
  `VoiceRoomActiveViewModel.kt`, servicios/fábricas de llamada.
- Administración: `AdminPanelScreen.kt`, `AdminPanelViewModel.kt`,
  `AdminModerationPage.tsx`, `admin-moderation-routes.js`.
- Medios/offline: `VideoRepository.kt`, `VideoPerformanceProfile.kt`,
  `UbuntuMedia.kt`, pantallas de Video/Social y componentes de caché.
- Radar: paquete Android `presentation/proximity`, `ProximityPage.tsx` y
  `proximity-routes.js`.
- Editor/recorte: `MessageMediaComposer.kt`, `SimpleImageCropDialog.kt`,
  `CreatePostDialog.kt`, `CreatePostScreen.kt`, `CreateMomentScreen.kt`,
  `RegisterScreen.kt`, `EditProfileScreen.kt`.
- Marca: recursos `strings.xml`, manifiesto web, layouts/auth web y textos
  visibles Android. El logo se conservó a petición del propietario.

## Verificación automática

- Android: 31 suites, 128 pruebas, 0 fallos y 0 errores.
- Android Debug: compilado; APK firmado de depuración.
- Android Release: `assembleRelease` completado con R8, lint vital y reducción de
  recursos. El APK resultante es **unsigned** porque no existe
  `keystore.properties` ni keystore Release en este entorno.
- Web: 19 pruebas de layout aprobadas y build Vite/TypeScript de producción.
- API: `npm run check`, `test:admin` y `test:proximity` aprobados.
- Signaling: `node --check signaling-server/index.js` aprobado.
- Overlay interno WebRTC: no quedan textos `IMPOLITE`, `HAVE_LOCAL_OFFER`,
  `Role`, `UserID` o `Sig` en las pantallas de llamada/sala.

## Pendiente físico y de entrega

- Debug se instaló con actualización `-r` en un `moto g04`, conservando datos.
  El arranque en frío completó en 6,986 ms, la actividad principal quedó
  reanudada, el proceso siguió vivo y no hubo crash/ANR inmediato. La jerarquía
  visible llegó a la pantalla funcional con “Radar”, “Aleatorio” y “Personas
  cerca”; no quedó detenida en el logo.
- Cámara, pantalla, altavoz, eco, llamada, sala y directos deben aprobarse con
  dos teléfonos, el mismo APK y redes diferentes siguiendo
  `WEBRTC_WIFI_MOBILE_TEST_MATRIX_2026-07-22.md`.
- Para publicar Release se debe recuperar/configurar la clave oficial de firma.
- Este bloque no fue desplegado a producción y no eliminó usuarios reales.
