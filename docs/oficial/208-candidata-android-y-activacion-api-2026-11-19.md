# 208 · Candidata Android y estado de incorporación en Ubuntu

19 de noviembre de 2026. La candidata Release de Novvin 9.1.43, código 183, se fijó al commit `5806a0cafb504237a05304d439be0160ec9007dd`, con árbol `285a9b820d68d2dbe1fbfbedbd3ef00776b6fdcb`. Incluye las correcciones de llamadas [200](200-resultado-y-cuenta-de-llamadas-2026-11-18.md), [202](202-primera-respuesta-llamadas-colectivas-2026-11-18.md), [205](205-sesion-de-controles-de-llamada-2026-11-18.md) y la protección de perfil [207](207-privacidad-y-formato-de-cumpleanos-2026-11-19.md). El checkout aislado conserva sus huellas públicas y privadas.

## Android

`assembleRelease` y `bundleRelease` terminaron en 22 min 20 s. APK y AAB pasaron 14 controles, incluidas la firma de carga, la alineación de 16 KiB y la coincidencia de 32 entradas DEX y nativas. Lint registró cero errores y 346 advertencias. La huella abreviada del commit aparece en el DEX de los dos paquetes.

| Archivo | SHA-256 |
| --- | --- |
| `Novvin-9.1.43-183-5806a0cafb50.apk` | `6dab6ce7a5c31dd1c61a8ccffc99abc354bf41fba19ed112fa6df968d5c3ebef` |
| `Novvin-9.1.43-183-5806a0cafb50.aab` | `f11fd2bba33fe5437b8e0244ae7331b0884e7df47472c4e03e41d61d9a182285` |

El recibo de `D:/Novvin-Entregas/evidencias/2026-11-18-mobile-release-check/5806a0cafb50/` separa los controles de APK/AAB, la configuración, la ejecución JVM y la prueba en Android. La revisión inmediatamente anterior, `0470542c5f1b`, también compiló y superó sus 14 controles de identidad, firma y binarios. Su alcance termina antes de 207.

La selección de llamadas y cumpleaños de la variante Release aprobó 56 de 56 casos en 11 clases, con 0 omisiones. Gradle también aprobó las guardas de origen HTTPS estable y de configuración de firma.

El ensayo de emulador quedó inconcluso. El recibo conserva el punto de interrupción. La recreación del acceso del APK exacto sigue pendiente.

Los tres dominios declarados para Android App Links respondieron con HTTP 200 directo y publicaron la asociación para el paquete y el certificado de carga del AAB de esta compilación. El recibo `app-links.json` conserva el resultado por dominio. La firma de distribución de Google Play sigue pendiente de contraste.

El paquete apunta a API 36. Google Play exige API 36 para envíos de aplicaciones Android móviles en el plazo vigente consultado y no admite reutilizar un código ya usado. La disponibilidad efectiva de 183, las pistas, los mercados habilitados y la aprobación de la ficha se comprueban en la cuenta de Play Console. [Nivel de API en Google Play](https://support.google.com/googleplay/android-developer/answer/11926878) · [Versionado Android](https://developer.android.com/studio/publish/versioning)

## Ubuntu

Seis muestras HTTPS de solo lectura al origen de Android devolvieron HTTP 200. Las medianas de las tres muestras fueron 31 ms para `/health` y 47 ms para `/ready` desde Windows. La inspección de la imagen y las huellas de dos módulos confirmó que la API activa todavía carece del contrato de regalos v2 y de las dos correcciones de privacidad y avisos publicadas.

Se prepararon los archivos para construir una imagen con cambios limitados a esos módulos y con la referencia de reversión del servicio anterior. Aprobaron 28 pruebas de regalos, cuatro escenarios HTTP con PostgreSQL 17 aislado y 24 controles de permisos y avisos. Se usaron identidades y saldos ficticios. Las huellas del servicio activo se verificaron de nuevo antes de conservar el paquete bajo `D:/Novvin-Entregas/evidencias/2026-11-19-production-gift-v2-candidate/ubuntu-combined-candidate/`. La activación y sus comprobaciones autenticadas siguen pendientes.

## Decisión de salida

Continúan abiertos los ensayos físicos con dispositivos simultáneos, cámaras, audio y cambio de red, la integración efectiva con Ubuntu y web, la regresión de arranque de 183 medida en [201](201-validacion-android-183-acceso-y-arranque-2026-11-18.md), la migración de elegibilidad y de fecha de nacimiento, y la revisión de mercados y datos del responsable. La [auditoría 187](187-auditoria-integral-y-criterios-de-produccion-2026-11-18.md) conserva los demás módulos y sus evidencias. La decisión de salida integral permanece abierta.

La galería estable de [209](209-altura-estable-de-fotos-en-el-feed-2026-11-19.md) se publicó después del código de esta compilación. Su validación binaria corresponde a una candidata posterior.
