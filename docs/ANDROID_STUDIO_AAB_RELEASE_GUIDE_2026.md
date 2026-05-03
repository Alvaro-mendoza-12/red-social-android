# Guía Detallada para Generar el `.aab` y Subirlo a Google Play

Esta guía está hecha para **este proyecto** y para tu flujo real en **Android Studio + Google Play Console**.

## 1. Estado actual del proyecto

Valores tomados de [app/build.gradle.kts](d:/red%20social%20-%20copia/app/build.gradle.kts):

- Módulo Android: `app`
- `applicationId`: `conectate.com`
- `versionCode`: `23`
- `versionName`: `2.1.3`
- `minSdk`: `26`
- `targetSdk`: `35`
- `compileSdk`: `35`

El proyecto **sí tiene configuración para firmado release**, pero solo se activa si existe el archivo local:

- `keystore.properties`

Ese archivo **no se sube a git** y en este workspace ahora mismo **no existe**. Por eso, si intentas compilar release sin configurarlo, puedes terminar con build incompleto para Play o con artefactos no firmados.

Importante:

- si usas el asistente `Build > Generate Signed Bundle / APK...`, Android Studio puede pedirte el keystore directamente y firmar desde ahí
- si quieres que `bundleRelease` o `assembleRelease` por Gradle/terminal salgan firmados de forma automática, sí necesitas `keystore.properties`

## 2. Qué necesitas antes de generar el AAB

Necesitas estas 4 cosas:

1. Un `keystore` de subida (`.jks` o `.keystore`)
2. El archivo local `keystore.properties`
3. Haber incrementado `versionCode` si vas a subir una versión nueva
4. Haber pasado el control de calidad local

## 3. Paso 1: prepara tu llave de subida

### Opción A: ya tienes tu archivo `.jks`

Si ya tienes tu llave, solo necesitas ubicar:

- ruta del archivo `.jks`
- `storePassword`
- `keyAlias`
- `keyPassword`

### Opción B: todavía no tienes tu llave

Puedes crearla desde Android Studio:

1. Abre Android Studio.
2. Ve a `Build > Generate Signed Bundle / APK...`
3. Elige `Android App Bundle`.
4. Pulsa `Create new...`
5. Guarda tu llave en una carpeta segura fuera del repo.
6. Completa:
   - `Key store path`
   - `Password`
   - `Key alias`
   - `Key password`
   - tus datos de certificado
7. Guarda ese `.jks` en un lugar seguro y haz backup.

Recomendación fuerte:

- guarda una copia en nube segura
- guarda otra copia offline
- no cambies de llave sin entender el impacto en Play App Signing

## 4. Paso 2: crea `keystore.properties`

En la raíz del repo ya existe este ejemplo:

- [keystore.properties.example](d:/red%20social%20-%20copia/keystore.properties.example)

Haz una copia local llamada:

- `keystore.properties`

Contenido esperado:

```properties
storeFile=C:\\ruta\\a\\tu\\upload-key.jks
storePassword=TU_PASSWORD_DEL_KEYSTORE
keyAlias=TU_ALIAS
keyPassword=TU_PASSWORD_DE_LA_KEY
```

Notas importantes:

- Puedes usar ruta absoluta de Windows.
- También puedes usar una ruta relativa al root del repo.
- No subas `keystore.properties` al repositorio.
- No subas el `.jks` al repositorio.

## 5. Paso 3: incrementa versión antes de compilar

Abre:

- [app/build.gradle.kts](d:/red%20social%20-%20copia/app/build.gradle.kts)

Y actualiza si corresponde:

```kotlin
versionCode = 24
versionName = "2.1.4"
```

Regla práctica:

- `versionCode` siempre debe subir
- `versionName` es la etiqueta visible para humanos

Si intentas subir a Google Play un `versionCode` repetido, Play lo rechazará.

## 6. Paso 4: corre la validación local antes del release

Desde terminal en la raíz del proyecto:

```powershell
.\tools\quality_gate.ps1
```

Si quieres validar también release APK:

```powershell
.\gradlew.bat :app:assembleRelease
```

## 7. Flujo recomendado en Android Studio para sacar el `.aab`

Este es el flujo que te recomiendo usar si vas a subir a Google Play.

### Opción recomendada: asistente de Android Studio

1. Abre el proyecto en Android Studio.
2. Espera a que termine el `Gradle Sync`.
3. Confirma que estás en el módulo correcto: `app`.
4. Ve a `Build > Generate Signed Bundle / APK...`
5. Elige `Android App Bundle`.
6. Pulsa `Next`.
7. Selecciona tu keystore:
   - `Key store path`
   - `Key alias`
   - `Key store password`
   - `Key password`
8. Pulsa `Next`.
9. Elige:
   - módulo `app`
   - variante `release`
10. Pulsa `Finish`.
11. Espera a que termine el proceso.

Cuando termine:

- abre el `Build Output`
- abre el `Event Log`
- usa `Locate` o `Show in Explorer`

Ruta Gradle esperada del bundle:

- `app/build/outputs/bundle/release/app-release.aab`

## 8. Flujo alternativo por línea de comandos

Si ya tienes `keystore.properties` creado:

```powershell
.\gradlew.bat clean :app:bundleRelease
```

Salida esperada:

- `app/build/outputs/bundle/release/app-release.aab`

Si además quieres APK release por ABI:

```powershell
.\gradlew.bat :app:assembleRelease
```

Salida esperada:

- `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`
- `app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk`

## 9. Qué subir a Google Play Console

Para Google Play debes subir:

- el archivo `.aab`

No subas a producción el APK local si tu objetivo es Play Console.

Ruta objetivo:

- `app/build/outputs/bundle/release/app-release.aab`

## 10. Orden recomendado de despliegue para esta app

Como esta versión también toca live streaming, el orden recomendado es:

1. Aplicar SQL en Supabase
2. Actualizar `signaling-server/index.js` en la VPS
3. Generar el `.aab` firmado
4. Subir primero a `Internal testing` en Google Play
5. Probar en dispositivo real desde Play
6. Luego pasar a `Production`

Para el lado VPS/live revisa también:

- [VOICE_VPS_RUNBOOK_2026.md](d:/red%20social%20-%20copia/docs/VOICE_VPS_RUNBOOK_2026.md)

## 11. Pasos en Google Play Console

Una vez tengas el `.aab`:

1. Entra a Google Play Console.
2. Abre tu app.
3. Ve a `Testing > Internal testing` si quieres validar primero.
4. Pulsa `Create new release`.
5. Sube el archivo `.aab`.
6. Escribe las notas de versión.
7. Guarda.
8. Revisa warnings o bloqueos.
9. Pulsa `Review release`.
10. Pulsa `Start rollout to Internal testing`.

Cuando valides bien:

1. Ve a `Production`.
2. Crea nueva release.
3. Sube el mismo `.aab` o una versión nueva si hiciste cambios.
4. Completa notas.
5. Envía a revisión.

## 12. Checklist corto antes de subir

- `versionCode` incrementado
- `versionName` actualizado si corresponde
- `quality_gate.ps1` en verde
- presupuesto de tamaño en verde para APKs por ABI y `.aab`
- live hotfix desplegado en VPS
- SQL de `live_streams.is_active = false` aplicado
- `keystore.properties` configurado
- `.aab` firmado generado
- prueba en `Internal testing` antes de producción

## 13. Problemas comunes y cómo resolverlos

### Problema 1: solo sale APK unsigned o no sale bundle firmado

Causa probable:

- falta `keystore.properties`
- o estás compilando release sin asistente de firmado

Qué hacer:

1. crea `keystore.properties`
2. verifica que el `.jks` exista en la ruta indicada
3. vuelve a ejecutar el proceso

### Problema 2: Google Play rechaza la subida

Causas comunes:

- `versionCode` repetido
- `.aab` no firmado con la upload key correcta
- package name incorrecto

Qué hacer:

1. sube `versionCode`
2. revisa la llave usada
3. confirma que la app es `conectate.com`

### Problema 3: `bundleRelease` falla en Windows con errores de archivos bloqueados

Durante esta verificación aparecieron dos patrones reales en este workspace:

- `Failed to create MD5 hash ... SplashScreenKt.class does not exist`
- `Unable to delete directory ... project_dex_archive ... DirectoryNotEmptyException`

Eso apunta a problema de **estado incremental / archivos bloqueados en `app/build/`**, no a un error funcional de la app.

Qué hacer en ese caso:

1. Cierra compilaciones activas en Android Studio.
2. Cierra emuladores si están usando el proyecto.
3. Ve a `Build > Clean Project`.
4. Luego `Build > Rebuild Project`.
5. Si sigue igual, cierra Android Studio.
6. Borra manualmente la carpeta `app/build`.
7. Abre Android Studio de nuevo.
8. Vuelve a correr `Generate Signed Bundle / APK`.
9. Si aún persiste, reinicia Windows para liberar locks del filesystem.

### Problema 4: contraseña o alias incorrectos

Síntomas:

- error de signing
- Android Studio no puede abrir el keystore

Qué hacer:

1. revisa `storePassword`
2. revisa `keyAlias`
3. revisa `keyPassword`
4. prueba el mismo keystore desde el asistente de Android Studio

## 14. Recomendación final

Para esta app, el flujo más seguro es:

1. configurar `keystore.properties`
2. correr `.\tools\quality_gate.ps1`
3. generar el bundle firmado desde Android Studio
4. subir a `Internal testing`
5. validar login, social, videos, live y notificaciones
6. recién después mandar a producción
