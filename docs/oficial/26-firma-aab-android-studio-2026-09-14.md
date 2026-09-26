# Firma AAB desde Android Studio

Fecha: 2026-09-14. Repositorio: `D:\red social - live-gifts`, rama `feature/live-gifts`.

## Incidencia y causa

El usuario informó de un fallo de Generate Signed Bundle en `:app:verifyReleaseSigningConfiguration`, antes de compilar, con “Release signing is required”. Indicó su llave existente en `D:\Novvin llave`. El archivo encontrado es `llaves de mi apk conecta` (2500 bytes, sin extensión); no fue renombrado, copiado al repositorio ni reemplazado.

La validación personalizada sólo reconocía `keystore.properties` o `NOVVIN_UPLOAD_*`. Ninguna de esas fuentes estaba configurada en esta sesión. Sin embargo, el asistente de Android Studio transmite su configuración con estas propiedades de Gradle:

- `android.injected.signing.store.file`
- `android.injected.signing.store.password`
- `android.injected.signing.key.alias`
- `android.injected.signing.key.password`

Se confirmó el contrato en las clases `StringOption` y `SigningOptions.readSigningOptions` del plugin Android Gradle **8.13.2 instalado**, sin acceder a secretos del IDE. La documentación oficial describe el [asistente para firmar APK/AAB](https://developer.android.com/studio/publish/app-signing#generate-key). El error informado demuestra que el guard no reconoció una fuente de configuración; no demuestra que la llave original, su contraseña o su certificado sean incorrectos.

## Corrección

- Se extrajo el guard a `gradle/release-signing-validation.gradle.kts` y se conserva su dependencia desde `preReleaseBuild`.
- Reconoce el conjunto completo de propiedades del asistente; el plugin Android continúa validando y utilizando la llave seleccionada. No se copian las credenciales del IDE a archivos ni se reemplaza su configuración.
- Si la selección del asistente está incompleta, falla con nombres de campos faltantes, sin mostrar valores y sin mezclar otra llave CLI automáticamente.
- Exige un archivo legible; una carpeta o una ruta inexistente no pasan. Sin asistente ni configuración CLI/CI válida, release sigue bloqueado.
- Se conserva el flujo de `keystore.properties` y `NOVVIN_UPLOAD_*`. Las contraseñas locales/de entorno ya no se recortan con `trim()`, para no alterar espacios que formen parte del secreto.
- No se ha añadido firma debug a release, generado una llave de reemplazo ni eliminado las validaciones de privacidad/producción.

## Evidencia y límites

1. Antes: se reprodujo el mismo error en la tarea real pasando metadatos de asistente y valores ficticios explícitos. `tmp/signing-wizard-before-20260914.log`.
2. Después: `:app:verifyReleaseSigningConfiguration` terminó con **BUILD SUCCESSFUL in 22s** con la configuración de fixture del asistente. `tmp/signing-wizard-after-20260914.log`. Sólo se ejecutó esa tarea: el archivo de fixture no es una llave y nunca firmó un artefacto.
3. **Ocho regresiones pasan** con el script real aplicado a un proyecto Gradle aislado: ausencia, CLI configurado, asistente completo/incompleto, prohibición de mezclar selección parcial con CLI, archivo inexistente, carpeta y precedencia del asistente. `tools/test-release-signing-gate.ps1`, fixtures en `tools/fixtures/release-signing`, log `tmp/signing-gate-regressions-20260914.log`.
4. No se dispone aquí de la contraseña/alias reales. Una lectura de keytool sin la contraseña real no pudo desbloquear el almacén; ese resultado **no se considera una prueba de contraseña equivocada del usuario ni de corrupción**. No se intentó recuperar contraseñas guardadas del IDE ni probar contraseñas alternativas.
5. No se generó ni subió un AAB firmado con la llave real. Sigue pendiente completar el asistente, verificar el certificado de carga contra Play Console y comprobar el bundle final. El bloqueo de privacidad documentado en los informes 24–25 permanece: corregir la firma no certifica que se pueda publicar con seguridad.

## Uso

En Android Studio, volver a **Build > Generate Signed Bundle / APK > Android App Bundle**. Seleccionar el archivo original `D:\Novvin llave\llaves de mi apk conecta` (no sólo la carpeta), su alias y contraseñas habituales, y la variante release. No crear otra llave. Este flujo ya no requiere duplicar credenciales en `keystore.properties`.

Para validar el AAB obtenido, usar `tools/verify_android_release.py` con la huella esperada del certificado de carga de Play Console, según el runbook 08. No confundir la huella debug instalada en el teléfono con la llave de carga de Play.
