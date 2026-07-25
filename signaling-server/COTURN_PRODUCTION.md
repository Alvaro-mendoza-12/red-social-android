# Coturn de producción

La configuración activa de Conéctate usa el VPS IONOS documentado en
`docs/VOICE_VPS_RUNBOOK_2026.md`.

## Instalación reproducible

Desde la raíz del repositorio, copie `scripts/provision-turn-vps.sh` al VPS y
ejecútelo como `root` proporcionando el secreto TURN por una variable de entorno
segura. El script configura Coturn, UFW, systemd, TLS y el hook de renovación del
certificado. No guarde el secreto en Git ni en el APK.

Valores operativos:

```text
IP pública: 198.251.72.174
Host: turn.198-251-72-174.sslip.io
TURN UDP/TCP: 3478
TURN TLS: 443
Relay UDP: 49160-49200
Realm: turn.198-251-72-174.sslip.io
```

El mismo secreto debe estar en `static-auth-secret` del VPS y en `TURN_SECRET`
del entorno privado del signaling. El signaling debe publicar:

```env
TURN_HOST=turn.198-251-72-174.sslip.io
TURN_PORT=3478
TURN_TLS_PORT=443
TURN_REALM=turn.198-251-72-174.sslip.io
TURN_TTL_SECONDS=3600
TURN_ENABLE_UDP=true
TURN_ENABLE_TCP=true
TURN_ENABLE_TLS=true
```

## Validación

1. `GET /health` debe indicar `turnConfigured: true` y proveedor Coturn REST.
2. `GET /turn-credentials` requiere Bearer válido y devuelve credenciales
   temporales, nunca el secreto compartido.
3. Validar asignaciones desde otra máquina por UDP 3478, TCP 3478 y TLS 443.
4. Validar el certificado contra el nombre, no contra la IP.
5. Ejecutar la matriz de `docs/WEBRTC_WIFI_MOBILE_TEST_MATRIX_2026-07-22.md` con
   dos dispositivos físicos antes de publicar el APK.

No habilite `allow-loopback-peers` en producción. Esa opción solo sirve para un
eco artificial ejecutado dentro del propio servidor y reduce el aislamiento de
destinos privados.
