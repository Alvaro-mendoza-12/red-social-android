# Estadísticas privadas del creador

Estado de esta fase: **preparado y desactivado**.

El API expone informes privados de videos y directos únicamente al propietario
del contenido. Android no calcula, completa ni simula métricas. Las cohortes
pequeñas ocultan retención, regiones, curvas y donantes mediante el umbral
`CREATOR_ANALYTICS_MIN_COHORT`.

## Variables

```dotenv
CREATOR_ANALYTICS_ENABLED=false
CREATOR_ANALYTICS_MIN_COHORT=10
CREATOR_ANALYTICS_HASH_SECRET=
```

`CREATOR_ANALYTICS_HASH_SECRET` debe generarse y guardarse solo en el servidor.
No debe reutilizar JWT, LiveKit, Firebase, Google Play ni otra credencial.

## Activación pendiente

Antes de habilitar la bandera se debe:

1. Aplicar `db/migrations/008_creator_analytics_v1.sql` en el PostgreSQL activo.
2. Generar un secreto HMAC aleatorio de al menos 32 bytes fuera de Git.
3. Publicar eventos desde fuentes canónicas confirmadas mediante un outbox
   transaccional:
   - vistas, reacciones, comentarios, compartidos y seguidores después del
     commit de su mutación;
   - entradas, salidas y presencia desde el gateway autoritativo del directo;
   - regalos únicamente después del commit monetario y contemplando su
     reversión/reembolso.
4. Verificar duplicados, reintentos, reembolsos y supresión de cohortes en la
   base activa antes de cambiar la bandera.

El método interno `recordCreatorAnalyticsEvent` valida, deduplica y
pseudonimiza actores/sesiones, pero deliberadamente no está conectado aún a
las rutas canónicas. Llamarlo desde Android o después de una respuesta HTTP
podría perder o duplicar eventos y producir informes financieros incorrectos.

## Endpoints privados

- `GET /v1/data/creator/analytics/content`
- `GET /v1/data/creator/analytics/video/:contentId`
- `GET /v1/data/creator/analytics/live/:contentId`

Todos requieren autenticación, comprueban propiedad en el servidor y responden
con `Cache-Control: private, no-store`. No devuelven identificadores de
espectadores ni de donantes.
