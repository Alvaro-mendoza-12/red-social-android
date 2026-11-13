# Estrategia de pruebas

Usar cuentas distintas para emisor, receptor y tercero. Guardar únicamente resultados sin identificadores privados. Primero ejecutar unidades Android y web. Después levantar PostgreSQL, la API, señalización, gateway y web en loopback con identidades propias del ensayo. Desactivar entrega de correo/push y todos los proveedores económicos. Comprobar dependencias del cliente tanto antes como después de recargar.

| Ensayo | Qué observar |
| --- | --- |
| Mensaje y archivo | Entrega en la pantalla ya abierta del receptor, nombre, bytes del original, reintento y lectura tras recarga. Repetir en privado, grupo y comunidad. |
| Suscripciones | Abrir más pantallas que el límite de suscripciones simultáneas, confirmar que se atiende la siguiente al recibir respuesta del gateway. Al reconectar recuperar el historial. |
| Accesibilidad | Teclado, foco, Escape, nombres accesibles, estado pulsado, visualización a 390, 1153 y 1355 px. Añadir lector físico y zoom en la siguiente ronda. |
| Llamadas | Dos agentes de usuario independientes. Recepción, permiso denegado, pista remota por extremo y liberación. En hardware medir candidato ICE, bytes y percepción audiovisual. |
| Tiempo y dinero | Usar PostgreSQL para transacciones concurrentes e idempotencia. Distinguir puntos de prueba y dinero real. |
| Producción | Validar respaldo, ensayo de restauración, rollback y decisión explícita de publicación. |

La participación en ensayos locales con cámaras sintéticas no cubre radio móvil. WCAG 2.2 orienta foco, reflujo, nombres y tamaño de los objetivos. ISO/IEC 25010 orienta el análisis de calidad del producto. Esta campaña aporta evidencia parcial sobre esos atributos. [W3C](https://www.w3.org/TR/WCAG22/) · [ISO](https://www.iso.org/standard/78176.html)
