# Entrada pública y monitor del servidor — 30 de septiembre de 2026

Trabajo en `D:\red social - live-gifts`, rama `feature/live-gifts`.

## Hallazgo y recuperación

El Motorola fallaba al conectar señalización y videos durante la primera
secuencia de 9.0.16. Los ocho contenedores seguían saludables. Forzar las dos
direcciones públicas del hostname de Tailscale produjo cierre TLS en ambas;
consultarlo desde un equipo con Tailscale podía entrar por la ruta privada y
ocultar el fallo público. Los detalles, la copia de configuración y la
repetición aprobada del Motorola están en
[la auditoría de medios](MEDIOS-Y-ENLACES-2026-09-30.md).

Se recuperó la entrada reiniciando únicamente `tailscaled`. No se modificaron
las rutas públicas ni se reinició la base de datos. Esa recuperación no prueba
una causa concreta del incidente ni garantiza disponibilidad futura de Funnel.

## Cambios del monitor existente

- `verify-public-ingress.js` consulta Google y Cloudflare por DNS HTTPS,
  acepta únicamente IPv4 públicas de respuestas relacionadas con el hostname
  y evita usar DNS del sistema para conectarse al destino. Conserva SNI y la
  verificación TLS del nombre original. Comprueba `/health`, `/ready` y el
  saludo de señalización Socket.IO en cada dirección. No imprime cuerpos,
  identificadores de sesión ni credenciales.
- Resolución y conexiones tienen tiempos máximos; la respuesta se limita a
  64 KiB y se comprueban hasta cuatro direcciones. Una entrada fallida se
  registra como degradación aunque otra siga disponible; direcciones sin
  comprobar impiden afirmar cobertura completa. No sustituye ensayos de
  autenticación, medios, IPv6 o carga.
- El monitor periódico ya instalado ejecuta primero ese sondeo público y
  después sus lecturas de Social y Mensajes. Se mantiene su umbral de tres
  fallos consecutivos. No reinicia servicios automáticamente.
- Mensajes admitía cuentas sin foto, pero su diagnóstico exigía una URL de
  avatar y acumulaba falsas alarmas. Ahora informa
  `avatarDelivery=not-exercised-no-avatar-url` cuando no hay foto disponible.
  Una foto disponible que no descarga sigue siendo un fallo; se mantienen
  las comprobaciones obligatorias de conversaciones e historial.

## Verificación y despliegue

- Windows: 16 pruebas aprobadas y una omitida por requerir Bash/Linux.
- Servidor Linux: dos pruebas del monitor aprobadas, incluyendo una entrada
  pública fallida con lecturas privadas correctas y la alerta tras tres fallos.
  Quince pruebas adicionales aprobadas dentro de la imagen real de API,
  con red deshabilitada y archivos montados en modo lectura.
- El primer ensayo Linux reveló que el Docker simulado del test no identificaba
  un comando que comenzaba por `run`; se corrigió el simulador y se repitió.
  No se registra ese primer ensayo como aprobado.
- Sondeo real desde la PC, forzando DNS público: seis comprobaciones HTTP 200
  con TLS válido y cuerpos esperados, 976–1.061 ms por solicitud. Evidencia
  local: `build/public-ingress-2026-09-30.json`.
- Scripts desplegados con copia previa en
  `/home/alvaro/conectate-local-fallback/backups/public-ingress-20260930/`.
  Se usó el acceso SSH por clave a la LAN conocida cuando Tailscale SSH volvió
  a pedir autenticación adicional. No se cambiaron políticas de acceso.
- Dos ejecuciones reales del monitor aprobaron sus tres sondeos a las
  11:57:51 y 11:59:12 UTC. Estado: `status=ok`, fallos consecutivos cero;
  ocho servicios Docker `running healthy`.

La APK 9.0.16 del Motorola aprobó sus trece ensayos ejecutados después de la
recuperación; el ensayo de dos equipos se realiza por separado. La salud de
contenedores, una lectura privada y una lectura pública son evidencias distintas.
