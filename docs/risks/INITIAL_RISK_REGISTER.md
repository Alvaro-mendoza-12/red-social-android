# Hallazgos al iniciar la revisión 124

| ID | Prioridad | Síntoma | Diagnóstico |
| --- | --- | --- | --- |
| R1 | P1 | El historial colectivo parece detenido | El gateway intentaba comprobar el identificador de un grupo contra la API de chats privados. El mensaje sí estaba en PostgreSQL. |
| R2 | P1 | El documento abre, pero pulsar descargar puede fallar | La descarga privada tenía una dependencia adicional del service worker pese a contar con una lectura autenticada funcional. |
| R3 | P1 | La llamada de la bandeja no lleva directamente a una persona | El flujo previo pedía un identificador técnico al usuario. |
| R4 | P1 | Se quedan rondas abiertas | El proceso de vencimiento emitía un error SQL en cada ciclo al activar el juego. |
| R5 | P1 | La ficha mostraba contadores sin recorrido y podía mezclar relaciones antiguas | Era necesario enlazar perfil y dirección, reconocer anulaciones y respetar privacidad en la lectura. |
| R6 | P2 | El selector ocupaba demasiado y repetía el mensaje de carga por baldosa | La barra auxiliar y la cuadrícula competían por altura en portátil. |

El [registro actual](RISK_REGISTER.md) documenta qué se corrigió y cómo se puede volver a comprobar.
