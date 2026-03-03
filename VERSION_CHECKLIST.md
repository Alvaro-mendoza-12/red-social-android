# Checklist de Pre-Lanzamiento

## ✅ Antes de Compilar

- [ ] Incrementar `versionCode` en `app/build.gradle.kts`
- [ ] Actualizar `versionName` si es necesario (ej: "1.0.0" → "1.1.0")
- [ ] Verificar que `buildTypes.release.minifyEnabled = true` (ProGuard activo)
- [ ] Limpiar proyecto: `.\gradlew.bat clean`

## ✅ Base de Datos (Supabase)

- [x] Script `fix_notifications_schema_complete.sql` ejecutado
- [x] Triggers verificados (follow, post_like, video_like)
- [x] RLS policies activadas y probadas
- [x] Foreign keys correctas
- [ ] Backup de base de datos creado (por si acaso)

## ✅ Testing en Dispositivos

- [ ] Probado en Android 7.0 (API 24 - mínimo soportado)
- [ ] Probado en Android 14 (API 34 - target actual)
- [ ] Probado en al menos 1 dispositivo físico
- [ ] Probado en al menos 1 emulador

### Funcionalidades Críticas a Probar

- [ ] Login/Registro funciona
- [ ] Subir video funciona
- [ ] Crear post funciona
- [ ] Enviar mensaje funciona (1:1, grupo, comunidad)
- [ ] Follow/Unfollow funciona
- [ ] Like en posts funciona
- [ ] Like en videos funciona
- [ ] **Notificaciones aparecen correctamente**
- [ ] **Badge de notificaciones se actualiza**
- [ ] Navegación desde notificación funciona

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
Salida APK: `app/build/outputs/apk/release/app-release.apk`

## ✅ Firma Digital

- [ ] Keystore localizado: `conectate-release-key.jks` (o tu nombre)
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

### Limpiar y Compilar Release
```powershell
cd "d:\red social"
.\gradlew.bat clean
.\gradlew.bat bundleRelease
```

### Ver versión actual
```powershell
# Buscar en el archivo:
# d:\red social\app\build.gradle.kts
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
