# 90 — Cierre de auditoría, red y presupuesto

Fecha: 29/10/2026, Bogotá. Continúa la [revisión 89](89-colas-por-cuenta-y-recuperacion-visor-2026-10-29.md). El propietario solicita una revisión final, preparación para dominio/correo propio y prioridades de compra con un presupuesto de S/300. Informa que usa WIN, aproximadamente S/60 mensuales, y recuerda una velocidad cercana a «200»; no se dispone del contrato ni del modelo del router/switch. No se realizaron compras ni cambios de plan.

## Servidor y red comprobados

La mini PC tiene **14 contenedores operativos**, saludables todos los que tienen healthcheck, y cero unidades systemd fallidas. La instantánea muestra aproximadamente **11 GiB de RAM disponibles**, 104 GB libres en raíz, 385 GB en el almacenamiento de Docker y 399 GB en el disco de respaldo. No se observa presión de CPU, RAM o disco en la muestra. Estos valores describen esa instantánea, no una certificación bajo carga sostenida.

La interfaz Ethernet `enp1s0f0` negocia **100 Mbps, full duplex**. `ethtool` confirma que la tarjeta soporta y anuncia **1000baseT/Full**; el otro extremo sólo anuncia 10/100. Por tanto, comprar otra tarjeta de red o más memoria no resuelve el límite observado. Deben comprobarse los puertos y cables de toda la ruta router → switch → servidor. Un puerto de 100 Mbps seguirá limitando la conexión aunque se use un cable de categoría superior.

**Unidades:** 100 Mbps equivalen a un máximo teórico de 12,5 MB/s antes de sobrecargas. Una cifra recordada de 200 para un plan de internet probablemente significa 200 Mbps, no 200 GB. El precio mensual no permite confirmar ni la velocidad contratada ni la simetría. [WIN](https://win.pe/) publica planes simétricos, pero no se atribuye uno concreto a esta cuenta sin su contrato.

Se ejecutaron tres descargas sintéticas de 10 MB y tres subidas de 5 MB al servicio público de medición de Cloudflare: **45 MB en total**, en peticiones secuenciales y con comprobaciones de disponibilidad. No se subieron archivos de usuarios. Los endpoints corresponden a los documentados por [Cloudflare Speedtest](https://github.com/cloudflare/speedtest).

| Muestra | Bajada | Subida |
|---|---:|---:|
| 1 | 39,73 Mbps | 23,62 Mbps |
| 2 | 44,04 Mbps | 64,36 Mbps |
| 3 | 36,09 Mbps | 21,32 Mbps |

Son transferencias breves que incluyen establecimiento de conexión y reflejan ese trayecto concreto. No son una prueba completa de la capacidad contratada, no miden el límite de Funnel y no demuestran que WIN incumpla el plan. La disponibilidad permaneció correcta. La muestra diferencial de diez segundos no encontró errores RX/TX; observó dos descartes RX sobre 17.312 paquetes, 0,011553 %. No se convierten los contadores acumulados de descartes en una supuesta pérdida actual de todos los usuarios.

La auditoría del host terminó **WARN únicamente por el enlace a 100 Mbps**, sin hallazgos críticos. La sonda local `/ready` respondió HTTP 200, TTFB 9,709 ms; esa cifra no representa la latencia de la aplicación ni una promesa a usuarios.

## Respaldo, correo, TLS y pagos

El respaldo diario del 29/10 terminó correctamente a las 03:27. Su copia externa coincide en fecha, y la sincronización finalizó a las 17:49. Se restauró realmente el `postgres.dump` del disco externo en PostgreSQL aislado: **256 tablas**, `RESTORE_ISOLATED_OK`. La instancia temporal usó red deshabilitada, sin puertos publicados y con límite de un núcleo/1 GiB; se retiró después de comprobar su etiqueta de propiedad. No se escribieron datos en producción. Esta prueba cubre restauración de base de datos sin propietarios/ACL originales; no se presenta como restauración completa de aplicación, permisos y archivos.

El disco externo está en el mismo servidor/local: protege frente a algunos fallos de disco, pero la revisión no acredita una copia cifrada fuera del local. Esa copia y la protección eléctrica merecen prioridad antes de comprar más RAM o CPU por anticipación.

**Correo:** la primera verificación SMTP de conexión/autenticación agotó su espera (`ETIMEDOUT`). El diagnóstico posterior comprobó conexión IPv4 y autenticación correcta tanto en 465/TLS como en 587/STARTTLS con el proveedor configurado. No se enviaron mensajes. La cola de recuperación tenía un envío confirmado y cero filas pendientes, en entrega o fallidas en las últimas 24 horas. Las **17 pruebas de recuperación y procedencia económica** aprobaron, incluidas las de fallos transitorios, reintentos acotados, invalidación de códigos, cifrado y borrado de payloads. El fallo inicial se conserva como incidencia transitoria observada; no se cambió el puerto ni se desactivó TLS para ocultarlo. Verificar autenticación SMTP no certifica la entrega de un correo a una bandeja real.

Las auditorías npm de dependencias de producción de la **web y de la imagen API en ejecución** informan **cero vulnerabilidades conocidas** a la fecha. No es una certificación de ausencia de toda vulnerabilidad. Los certificados HTTPS se validaron correctamente: backend con 33 días restantes y web con 89 días en la muestra.

Se contrastaron hashes normalizados de cuatro módulos dentro de la imagen activa: payouts, transporte SMTP, cola de correo y procedencia económica. **Los cuatro coinciden con las fuentes revisadas y probadas.** Esta comprobación no incluye la consulta de perfiles candidata pendiente de despliegue.

**Retiros:** el servidor mantiene `enabled=false`, modo sandbox, proveedor y webhook sin configurar. Se conservan las guardas de cuenta verificada, saldo, límites e idempotencia; no se activan pagos para aparentar disponibilidad. La revisión dirigida de payouts y readiness aprobó cuatro casos ejecutados y dejó uno omitido por requerir Linux; la restauración real aislada se comprobó aparte. [PayPal Payouts](https://developer.paypal.com/payouts/use-payouts/overview) exige cuenta Business, acceso aprobado, identidad/correo/banco confirmados y fondos suficientes. Un correo corporativo por sí solo no habilita retiros; también faltan las credenciales y pruebas controladas de integración en vivo.

## Web y Android

La comprobación nueva del dominio público aprobó **90/90 controles HTTP/SEO**. El canario del servidor para ingreso público, Social/multimedia y Mensajes está en estado correcto, sin fallos consecutivos. No se repitieron baterías completas aprobadas una hora antes sin cambios de código: siguen vigentes las **857 pruebas web, 96 Node, 1.956 unitarias Android y ocho nativas** de 89, con sus alcances y advertencias documentados.

Se generaron los paquetes normales **9.1.30 / 170**, con optimización R8, recursos reducidos y firma de producción. APK y AAB verifican criptográficamente y conservan el certificado local de subida contrastado con el APK firmado 169. Paquete `conectate.com`, mínimo API 24, objetivo **API 36**, sin `debuggable` y entorno production. El objetivo corresponde al [requisito vigente de Play](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en). Bundletool valida el AAB; R8 conserva sus **17 componentes de manifiesto** y clases críticas; **14 bibliotecas de 64 bits** aprueban alineación ELF de 16 KiB, además de ZIP y configuración de empaquetado del bundle.

- APK SHA-256: `1ce6f920b708ac91231d0a877df3fbf96f4ea56f41d3d89c47d7dbc66bbd21d5`.
- AAB SHA-256: `47b89ad654b47ca09c1be87e1f8260b020af3d43ec7f93ab3965b71a08e4548a`.
- Commit embebido de compilación: `5d5dc40def76`, `main` antes de este cierre documental. El código funcional coincide con ambas ramas; los commits posteriores de esta revisión actualizan documentación, no el APK ya verificado.

Se instaló ese **APK firmado concreto** en el emulador propio AOSP 35 y se comprobó bienvenida, formulario de acceso, primer paso de registro, entrada como invitado, perfil invitado, salida y reinicio en frío después de salir. No se registraron cuentas, no se enviaron correos ni mensajes y no se observó un crash de `conectate.com`. El emulador presentó crashes del servicio del sistema `com.android.bluetooth` durante su arranque: se conservan como incidencias del entorno, no se ocultan ni se atribuyen a Novvin; no se valida audio Bluetooth. El emulador se cerró al terminar.

Este smoke de release no sustituye registro/login completos, restauración autenticada real, verificación física ni comunicaciones entre redes. **No se ha publicado en Play Console**. Antes de cargar el AAB debe contrastarse que 170 sigue disponible en la consola; el certificado local de subida no demuestra cuál es el certificado con que Play firma la aplicación instalada.

## Dominio y presupuesto de S/300

Recomendación basada en el límite medido: conservar el servidor actual y priorizar red, identidad pública y continuidad. Las partidas siguientes son **topes de presupuesto**, no una orden de compra ni una cotización final.

| Prioridad | Partida | Reserva |
|---|---|---:|
| 1 | Switch de cinco puertos 10/100/1000, si hace falta mantener el switch en la ruta | S/50 |
| 2 | Cableado Cat6 de cobre, longitudes reales y conectores correctos en ambos tramos | S/40 |
| 3 | Dominio por un año, precio normal y renovación contrastados | S/60 |
| 4 | Fondo para UPS adecuado o copia cifrada fuera del local | S/150 |
| | Total | **S/300** |

Referencia de precio consultada: [TP-Link LS1005G en Memory Kings](https://www.memorykings.pe/producto/347809/switch-gbe-5pt-tp-link-ls1005g-escritorio), **S/41,50**, IGV incluido y envío aparte. El [fabricante](https://www.tp-link.com/us/home-networking/5-port-switch/ls1005g/) confirma cinco puertos 10/100/1000. Hay cables Cat6 cortos desde S/8,50 en el [catálogo local](https://www.memorykings.pe/listados/857/cables-red-patch-cord), pero no se certifica que todos los modelos sean 100 % cobre: comprobar material y longitud antes de pagar. No hace falta Cat7 para el objetivo de un enlace gigabit; la [documentación de TP-Link](https://static.tp-link.com/upload/manual/2025/202509/20250915/7100001585_POE20E-Outdoor%28UN%291.0_IG_REV1.0.1.pdf) admite Cat5e o superior para 1000BASE-T hasta 100 m.

Si el router dispone de un puerto gigabit libre y la instalación lo permite, conectar el servidor directamente con un cable adecuado permite comprobar primero el resultado y podría ahorrar el switch. El cambio físico queda pendiente del propietario. Tras conectarlo, confirmar negociación a 1000 Mbps y medir nuevamente subida, bajada y latencia; un switch gigabit no aumenta el plan contratado ni elimina otros límites del trayecto.

Referencia para dominio `.com` ordinario: [Porkbun](https://porkbun.com/tld/com) publica **US$11,08**. La reserva de S/60 cubre un margen orientativo; el cobro depende del nombre disponible, tipo de cambio, impuestos y precio de renovación. Evitar asumir ese importe para nombres premium. No se compra el dominio sin conocer el nombre elegido.

Para una primera casilla humana, [Zoho Mail](https://www.zoho.com/es-xl/mail/zohomail-pricing.html) publica un plan gratuito con dominio propio de hasta cinco usuarios, disponible sólo en determinados centros de datos y con restricciones. Confirmar disponibilidad al crear la cuenta. Una casilla o reenvío para contacto no sustituye automáticamente el servicio SMTP/transaccional de recuperación que ya utiliza el servidor. Configurar SPF, DKIM y DMARC con las instrucciones del proveedor elegido.

Un UPS merece prioridad si el servidor no lo tiene: escogerlo por consumo en W, autonomía requerida, router/switch que deba sostener y posibilidad de apagado seguro; la reserva no garantiza que un modelo con esas capacidades cueste S/150. Los puertos USB de carga no acreditan comunicación de datos para apagado automático. La copia fuera del local debe quedar cifrada, con una clave recuperable separada del servidor y una prueba de restauración.

## Preparación del dominio y límites de producción

El dominio mejorará identidad, enlaces y correo. No multiplica por sí mismo el ancho de banda. El ingreso API actual usa Tailscale Funnel: la [documentación oficial](https://tailscale.com/docs/features/tailscale-funnel) limita sus nombres a `ts.net` y declara límites de ancho de banda no configurables. No basta crear un CNAME al Funnel y afirmar que el backend ya usa un dominio propio con TLS correcto. Antes de sustituir ese ingreso deben contrastarse HTTPS, HTTP Range, tamaño de cargas, WebSockets y medios WebRTC con el transporte elegido.

Al recibir el dominio: configurar web y DNS/TLS; actualizar origen público, canonical/sitemap y enlaces compartidos; revisar orígenes permitidos y retornos de autenticación; verificar rutas de voz/perfil al entrar sin sesión; configurar correo y autenticación del dominio; y publicar la asociación Android con la huella SHA-256 de firma real de Play, que sigue pendiente. Conservar los enlaces anteriores durante la transición. No se añaden comodines a los orígenes de confianza sin conocer el dominio real.

La optimización SQL de perfiles medida en la copia aislada de 85 **sigue sin desplegarse**. No se afirma que sus 8,6 ms estén disponibles en producción ni que un cambio de cable resuelva ese coste SQL. Tampoco se aprueba una campaña masiva: las pruebas de capacidad anteriores encontraron degradación y tienen límites de concurrencia, duración y tipo de tráfico. El cierre distingue continuar operando con vigilancia, distribuir una actualización, habilitar retiros y aumentar tráfico; cada acción tiene pendientes distintos.

**Dictamen:** no se encontraron nuevos defectos funcionales persistentes que exigieran cambiar el código en esta tanda. Se confirma el límite físico de red y una incidencia SMTP transitoria, cuyo diagnóstico recuperó autenticación y cuya clase de fallo tiene reintentos persistentes probados. Puede mantenerse la operación actual con observación; quedan artefactos release locales verificados para revisión de distribución. No se presenta una certificación absoluta de producción, pagos habilitados ni capacidad masiva. El dominio, el correo elegido y la mejora física de red se incorporarán cuando el propietario disponga de ellos, con las comprobaciones anteriores.
