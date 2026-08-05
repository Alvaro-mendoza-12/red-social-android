# Directos: habilitación de LiveKit y compras de monedas

Esta rama deja el contrato de invitados/batallas, el endpoint de token SFU y la
verificación server-side de compras listos para conectar. Las dos integraciones
se activan por variables de entorno; por defecto permanecen apagadas para no
permitir salas ni cobros accidentales.

## LiveKit

Las credenciales de LiveKit sólo se configuran en el `.env` del servidor Ubuntu
(o en el gestor de secretos del proveedor). Nunca deben entrar en
`secrets.properties`, `BuildConfig`, el APK, logs, capturas ni commits:

```env
LIVE_GUESTS_BATTLES_ENABLED=true
LIVE_SFU_ENABLED=true
LIVE_MAX_GUESTS=4
LIVEKIT_URL=wss://tu-proyecto.livekit.cloud
LIVEKIT_API_KEY=...
LIVEKIT_API_SECRET=...
```

Después de copiar el código a la VM:

```bash
cd ~/conectate-local-fallback
bash scripts/sync-env-from-example.sh
# Editar .env sólo en el servidor y agregar las variables anteriores.
docker compose up -d --build api edge signaling
docker compose exec api node scripts/verify-live-production-config.js
```

La prueba sólo muestra banderas, presencia de valores y la validación de un JWT
efímero; nunca imprime la clave ni el secreto. El token Android lo solicita a
`POST /v1/data/live/:liveId/sfu-token` con el JWT normal de la sesión. El APK
recibe únicamente `wsUrl`, `roomName` y un token de 10 minutos.

Si alguna clave fue pegada en un chat o en un ticket, debe revocarse y generarse
otra en LiveKit Cloud antes de activar producción.

## Compras y recarga

La aplicación usa Google Play Billing para monedas consumibles. El servidor
verifica cada token con Android Publisher, valida el `productId`, comprueba el
estado completado, acredita de forma idempotente y consume el producto. No se
deben solicitar números de tarjeta, PAN ni CVV al usuario.

En el servidor se necesitan:

```env
LIVE_COIN_PURCHASES_ENABLED=true
GOOGLE_PLAY_PACKAGE_NAME=conectate.com
GOOGLE_PLAY_COIN_PACKAGES_JSON={"novvin_coins_70":70,"novvin_coins_140":140,"novvin_coins_350":350,"novvin_coins_700":700,"novvin_coins_1400":1400,"novvin_coins_3500":3500,"novvin_coins_7000":7000,"novvin_coins_14000":14000}
GOOGLE_PLAY_SERVICE_ACCOUNT_DIR=/home/alvaro/conectate-local-fallback/secrets
GOOGLE_PLAY_SERVICE_ACCOUNT_FILE=/run/novvin-secrets/google-play-service-account.json
```

Instala el archivo desde un canal seguro, sin copiarlo al repositorio ni al
`.env`:

```bash
cd ~/conectate-local-fallback
install -d -m 700 "$HOME/conectate-local-fallback/secrets"
install -m 600 /ruta/temporal/nueva-red-social-c9d04-6b94a68481fe.json \
  "$HOME/conectate-local-fallback/secrets/google-play-service-account.json"
python3 - <<'PY'
import json
from pathlib import Path
json.loads(Path.home().joinpath('conectate-local-fallback/secrets/google-play-service-account.json').read_text())
print('JSON válido')
PY
rm -f /ruta/temporal/nueva-red-social-c9d04-6b94a68481fe.json
```

El servicio `api` monta ese directorio como `/run/novvin-secrets` en modo solo
lectura. La aplicación lee el archivo mediante
`GOOGLE_PLAY_SERVICE_ACCOUNT_FILE`; el JSON no se imprime ni se registra.

Antes de encender la bandera:

1. En Play Console crear los ocho productos consumibles con exactamente esos
   IDs, precios y países objetivo.
2. Crear una cuenta de servicio de Google Cloud, habilitar **Google Play
   Android Developer API** y darle acceso a la aplicación en Play Console.
3. Guardar el JSON sólo como archivo secreto del backend; nunca en Git, `.env`
   ni Android.
4. Aplicar la migración `sql/migrations/20260802_coin_purchase_ledger_v5.sql`
   en la base de producción.
5. Ejecutar `node scripts/verify-live-production-config.js` y confirmar que
   `billing.ready` sea `true`.
6. Publicar primero en una pista interna de Play Console y hacer una compra de
   prueba. La acreditación debe aparecer una sola vez y el historial debe
   conservar el `provider_reference`.

Las retiradas del creador no se liquidan con Google Play: requieren un proveedor
de payouts/KYC separado (por ejemplo, la cuenta empresarial y credenciales del
PSP que se elija) y un flujo legal/fiscal independiente. No se activa ni se
simula ese dinero retirable con las credenciales de compra.

## Qué debe aportar el propietario para producción

- Acceso de administrador a Play Console para publicar los productos y otorgar
  permisos a la cuenta de servicio.
- El JSON de la cuenta de servicio Android Publisher, entregado por un canal de
  secretos, no por el repositorio ni por el APK.
- Confirmación del `applicationId` y del certificado de firma de la versión que
  se subirá.
- Para retiros: país/entidad legal, proveedor de payouts elegido y sus claves
  server-side, además de la configuración KYC exigida por ese proveedor.

Con esos datos se puede activar la bandera gradualmente. Sin la cuenta de
servicio y los productos publicados, el botón de recarga debe seguir mostrando
“compras no disponibles” y no debe iniciar ningún cobro.
