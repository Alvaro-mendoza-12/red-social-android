# Checklist de Pre-Lanzamiento

Guía detallada para generar el `.aab` en Android Studio y subirlo a Google Play:

- [docs/ANDROID_STUDIO_AAB_RELEASE_GUIDE_2026.md](d:/red%20social%20-%20copia/docs/ANDROID_STUDIO_AAB_RELEASE_GUIDE_2026.md)

## ✅ Antes de Compilar

- [ ] Incrementar `versionCode` en `app/build.gradle.kts`
- [ ] Actualizar `versionName` si es necesario (ej: "1.0.0" → "1.1.0")
- [ ] Confirmar que `keystore.properties` existe y apunta a tu `.jks`
- [ ] Limpiar proyecto: `.\gradlew.bat clean`

## ✅ Base de Datos (Supabase)

- [x] Script `fix_notifications_schema_complete.sql` ejecutado
- [x] Triggers verificados (follow, post_like, video_like)
- [x] RLS policies activadas y probadas
- [x] Foreign keys correctas
- [ ] Backup de base de datos creado (por si acaso)
- [ ] Ejecutado en producción:
  - `alter table public.live_streams alter column is_active set default false;`
- [ ] Si hay lives fantasma y no hay transmisiones activas reales:
  - `update public.live_streams set is_active = false, viewer_count = 0 where is_active = true;`

## ✅ Infraestructura Live / VPS

- [ ] Confirmado VPS de voz:
  - ruta `/srv/signaling-server`
  - servicio `signaling-server.service`
- [ ] Backup del signaling realizado antes del cambio:
  - `cp index.js "index.js.bak-$(date +%F-%H%M%S)"`
- [ ] `index.js` actualizado en VPS con la versión local de `signaling-server/index.js`
- [ ] Servicio reiniciado:
  - `systemctl restart signaling-server.service`
  - `systemctl status signaling-server.service --no-pager`
- [ ] Logs revisados:
  - `journalctl -u signaling-server.service -n 100 --no-pager`
- [ ] Endpoints validados:
  - `curl -I https://voice.srv1495648.hstgr.cloud/health`
  - `curl "https://voice.srv1495648.hstgr.cloud/live-active-ids"`
- [ ] Si `/live-active-ids` responde `404`, revisar proxy/Caddy para que todo el host haga `reverse_proxy 127.0.0.1:3001`

## ✅ Testing en Dispositivos

- [ ] Probado en Android 8.0 (API 26 - mínimo soportado actual)
- [ ] Probado en Android 14/15 (API 34/35)
- [ ] Probado en al menos 1 dispositivo físico
- [ ] Probado en al menos 1 emulador

### Funcionalidades Críticas a Probar

- [ ] Login/Registro funciona
- [ ] Subir video funciona
- [ ] Subir video de hasta **30 segundos** funciona
- [ ] Crear post funciona
- [ ] Enviar mensaje funciona (1:1, grupo, comunidad)
- [ ] Follow/Unfollow funciona
- [ ] Like en posts funciona
- [ ] Like en videos funciona
- [ ] **Notificaciones aparecen correctamente**
- [ ] **Badge de notificaciones se actualiza**
- [ ] Navegación desde notificación funciona
- [ ] En `Social`, sin internet, aparece:
  - `No tienes conexión a internet`
  - botón `Intentar de nuevo`
- [ ] En `Videos`, sin internet, aparece:
  - `No tienes conexión a internet`
  - botón `Intentar de nuevo`
- [ ] Prueba crítica de live:
  - iniciar live
  - cerrar app o recompilar sin finalizar
  - esperar 90 segundos
  - verificar que el live desaparece del feed si el host no vuelve

## ✅ Logs y Errores

- [ ] Revisar Logcat durante ejecución → sin errores críticos
- [ ] Revisar `app/build/outputs/logs/` → sin warnings graves
- [ ] Probar con modo avión → la app no crashea (solo muestra error de red)

## ✅ Compilación

### Debug (para testing)
```powershell
.\gradlew.bat assembleDebug
```
Salida: `app/build/outputs/apk/debug/app-debug.apk`

### Release (para Google Play)
```powershell
.\gradlew.bat bundleRelease
# O si necesitas APK firmado:
.\gradlew.bat assembleRelease
```
Salida AAB: `app/build/outputs/bundle/release/app-release.aab`
Salida APK ABI 64-bit: `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`
Salida APK ABI 32-bit: `app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk`

## ✅ Firma Digital

- [ ] Keystore localizado: `conectate-release-key.jks` (o tu nombre)
- [ ] `keystore.properties` creado desde `keystore.properties.example`
- [ ] Contraseña del keystore recordada/guardada
- [ ] AAB/APK firmado correctamente
- [ ] Backup del keystore en lugar seguro (Google Drive, USB, etc.)

## ✅ Google Play Console

- [ ] Cuenta de desarrollador activa
- [ ] App creada en Play Console
- [ ] Notas de la versión escritas
- [ ] Screenshots actualizados (si hay cambios visuales)
- [ ] Descripción de la app actualizada (opcional)

## ✅ Post-Lanzamiento

- [ ] Verificar que la versión aparece en Play Console
- [ ] Esperar aprobación (1-7 días)
- [ ] Probar descarga desde Play Store en dispositivo limpio
- [ ] Verificar actualización automática para usuarios existentes
- [ ] Monitorear crashes en Firebase Crashlytics (si lo tienes configurado)
- [ ] Revisar reviews de usuarios en Play Store

---

## 📋 Comandos Rápidos

### Quality Gate local
```powershell
.\tools\quality_gate.ps1
```

### Limpiar y Compilar Release
```powershell
cd "d:\red social - copia"
.\gradlew.bat clean
.\gradlew.bat bundleRelease
```

### Ver versión actual
```powershell
# Buscar en el archivo:
# d:\red social - copia\app\build.gradle.kts
# Línea: versionCode = X
```

### Firmar manualmente (si es necesario)
```powershell
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore conectate-release-key.jks app-release.aab conectate
```

---

## 🎯 Versión Actual

- **Version Code**: ___ (incrementar antes de compilar)
- **Version Name**: ___
- **Fecha de Build**: ___
- **Fecha de Subida a Play Console**: ___
- **Estado**: [ ] Testing / [ ] Producción
