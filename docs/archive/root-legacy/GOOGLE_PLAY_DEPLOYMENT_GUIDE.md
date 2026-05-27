# Guía de Despliegue en Google Play Console

## 📦 Paso 1: Preparar el APK/AAB

### Opción A: Generar APK (Recomendado para Testing)
```powershell
cd "d:\red social"
.\gradlew.bat assembleRelease
```
El APK estará en: `d:\red social\app\build\outputs\apk\release\app-release.apk`

### Opción B: Generar AAB (Recomendado para Producción)
```powershell
cd "d:\red social"
.\gradlew.bat bundleRelease
```
El AAB estará en: `d:\red social\app\build\outputs\bundle\release\app-release.aab`

> **⚠️ IMPORTANTE**: Google Play **requiere** formato AAB para nuevas apps desde Agosto 2021. Usa APK solo para testing interno o distribución fuera de Play Store.

---

## 🔐 Paso 2: Firmar la Aplicación

Si el archivo no está firmado, verás un error. Necesitas firmar con tu keystore:

```powershell
# Ubicar tu keystore (deberías tenerlo guardado de la primera versión)
# Si no lo tienes, créalo con:
keytool -genkey -v -keystore conectate-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias conectate

# Firmar el AAB
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore conectate-release-key.jks app-release.aab conectate
```

> **🔴 CRÍTICO**: Guarda tu keystore en un lugar seguro. Si lo pierdes, **nunca podrás actualizar tu app** en Google Play.

---

## 🚀 Paso 3: Subir a Google Play Console

### 3.1 Acceder a Play Console
1. Ve a https://play.google.com/console
2. Inicia sesión con tu cuenta de desarrollador
3. Selecciona tu aplicación "Conectate" (o como se llame)

### 3.2 Incrementar el Código de Versión

**ANTES de compilar**, edita `d:\red social\app\build.gradle.kts` y busca:

```kotlin
defaultConfig {
    applicationId "conectate.com"
    minSdk = 24
    targetSdk = 34
    versionCode = 1  // ← INCREMENTA ESTO (debe ser mayor que la versión actual en Play Store)
    versionName = "1.0.0"  // ← Opcional: cambiar a "1.1.0" o similar
}
```

**Ejemplo**: Si la versión actual en Play Store es `versionCode = 5`, cambia a `versionCode = 6`.

Luego vuelve a generar el AAB:
```powershell
.\gradlew.bat bundleRelease
```

### 3.3 Crear una Nueva Versión

1. En Play Console, ve a **"Producción"** (o "Testing interno/cerrado" si prefieres probar primero)
2. Click en **"Crear nueva versión"**
3. Click en **"Subir"** y selecciona tu archivo `.aab`
4. Espera a que se procese (1-2 minutos)

### 3.4 Rellenar Información de la Versión

#### Nombre de la versión
```
1.1.0 - Sistema de Notificaciones
```

#### Notas de la versión (What's new)
**Español** (máximo 500 caracteres):
```
🎉 ¡Notificaciones en tiempo real!
✅ Recibe alertas cuando te siguen o dan like
✅ Mensajería mejorada (sin pérdida de mensajes)
✅ Badges de usuario visibles
🐛 Correcciones de bugs y mejoras de rendimiento
```

**Inglés** (si tu app es multiidioma):
```
🎉 Real-time notifications!
✅ Get alerts for follows and likes
✅ Improved messaging (no message loss)
✅ User badges now visible
🐛 Bug fixes and performance improvements
```

### 3.5 Revisar y Enviar

1. Revisa que el `versionCode` sea mayor que el anterior
2. Verifica que el tamaño del archivo sea razonable (< 150 MB generalmente)
3. Click en **"Revisar versión"**
4. Click en **"Iniciar despliegue en producción"**

---

## ⏱️ Paso 4: Tiempos de Procesamiento

- **Revisión inicial**: 1-3 horas (automática)
- **Revisión completa**: Hasta 7 días (si hay revisión manual)
- **Disponibilidad para usuarios**: 2-24 horas después de aprobación

---

## 🧪 Paso 5: Testing Recomendado (Opcional pero Importante)

Antes de ir a producción, haz un **Testing Cerrado**:

1. En Play Console → **Testing** → **Testing cerrado**
2. Crea una nueva versión (igual que en producción)
3. Sube el `.aab`
4. Añade emails de testers (máximo 100 en lista cerrada)
5. Los testers recibirán un link para descargar desde Play Store

**Ventaja**: Puedes probar con usuarios reales sin afectar la versión en producción.

---

## 📝 Checklist Final

Antes de publicar, verifica:

- [ ] `versionCode` incrementado en `build.gradle.kts`
- [ ] Archivo `.aab` generado y firmado
- [ ] Notas de la versión escritas en todos los idiomas soportados
- [ ] Probado en al menos 2 dispositivos diferentes
- [ ] Base de datos Supabase actualizada (scripts SQL ejecutados)
- [ ] Sin errores críticos en logs
- [ ] Funcionalidad de notificaciones verificada
- [ ] Keystore guardado en lugar seguro (backup)

---

## 🆘 Problemas Comunes

### "El código de versión debe ser mayor"
→ Incrementa `versionCode` en `build.gradle.kts`

### "Firma no válida"
→ Asegúrate de usar el mismo keystore que en versiones anteriores

### "El bundle es demasiado grande"
→ Activa ProGuard/R8 para reducir tamaño (ya debería estar activo)

### "Error al subir el archivo"
→ Usa AAB en lugar de APK para producción

### "La revisión está tardando mucho"
→ Normal. Puede tomar hasta 7 días. Sé paciente.

---

## 📞 Soporte

Si tienes problemas con Google Play Console:
- Centro de ayuda: https://support.google.com/googleplay/android-developer
- Comunidad: https://support.google.com/googleplay/android-developer/community
