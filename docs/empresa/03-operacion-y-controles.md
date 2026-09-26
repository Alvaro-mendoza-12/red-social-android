# Operación, cambios, releases e incidentes

## Flujo de cambio

1. Registrar problema, impacto, alcance y propietario.
2. Clasificar P0 crítico, P1 alto, P2 medio o P3 bajo.
3. Definir aceptación observable y riesgo de regresión.
4. Implementar el cambio mínimo coherente y actualizar pruebas.
5. Ejecutar controles locales y de integración.
6. Para multimedia, validar al menos dos dispositivos y una red móvil antes de afirmar compatibilidad general.
7. Preparar versión, notas, migración, backup y rollback.
8. Aprobar, desplegar gradualmente, observar y cerrar con evidencia.

## Severidades

- P0: pérdida/exposición de datos, acceso no autorizado, indisponibilidad total, cobros incorrectos o contenido peligroso sin control. Mitigación inmediata.
- P1: función principal inutilizable, video/audio roto ampliamente, datos inconsistentes o eliminación que no se propaga. Prioridad del release actual.
- P2: degradación parcial, rendimiento, compatibilidad limitada o flujo confuso con alternativa.
- P3: detalle visual, texto, refactor o mejora sin impacto funcional relevante.

## Release Android

- Confirmar rama, árbol de trabajo y cambios incluidos.
- Subir `versionCode` y `versionName` solo al preparar el artefacto definitivo.
- Ejecutar unit tests, lint/compilación release y pruebas instrumentadas relevantes.
- Firmar con la clave oficial; nunca sustituirla por una clave temporal para Play Store.
- Probar instalación/actualización del mismo artefacto release en dispositivo físico.
- Validar login, cambio de cuenta, videos, momentos, mensajes, notificaciones y rutas críticas.
- Publicar por porcentaje cuando sea posible y vigilar ANR, crashes, latencia y reproducción.

## Cambio de servidor

- Tomar backup verificable y registrar espacio disponible.
- Validar configuración y secretos sin imprimirlos.
- Desplegar con marcador atómico y rollback.
- Verificar salud de API, PostgreSQL, realtime, signaling, IA, edge, túnel y storage.
- Ejecutar smoke tests externos desde una red distinta.

## Incidentes

El comandante del incidente coordina; quien investiga no debe ocultar incertidumbre. El registro incluye hora, síntoma, alcance, mitigación, evidencia y comunicación. P0/P1 requieren postmortem sin culpa con causa técnica y sistémica, acciones, propietario y fecha objetivo.

## Cambios prohibidos sin aprobación explícita

- Borrado masivo o formateo.
- Rotación de firma, dominios, claves o credenciales.
- Migraciones destructivas.
- Publicación productiva, gasto o cambio legal.
