# Continuidad del proyecto

Lee primero `docs/oficial/README.md` y el apartado **Estado vigente** de
`docs/oficial/101-mobile-inicio-cuentas-y-comentarios-2026-10-31.md`.
La revisión integral sigue abierta. Los informes históricos, sus cifras y sus
paquetes no son el estado actual de todo el proyecto.

## Acuerdos del propietario

- Trabaja directamente, sin crear ni reanudar agentes o subagentes.
- Completa primero la revisión y corrección móvil; después continúa con la web y
  su integración. Una prueba aprobada no cierra los módulos no revisados.
- Publica las correcciones terminadas en `main` y `feature/live-gifts`, con árboles
  iguales. Usa los worktrees existentes y comprueba cambios ajenos antes de editar.
- No modifiques `correciones`: pertenece al otro chat.
- Las funciones y los rediseños del plan experimental 105 se desarrollan y
  publican exclusivamente en `codex/experiencias-con-proposito`. El propietario
  confirmó esta separación el 4 de noviembre. La regla de publicar en las dos
  ramas estables se aplica a sus correcciones, y la incorporación de los
  experimentos requiere una nueva instrucción expresa del propietario.
- No borres a ciegas respaldos, entregas, claves ni temporales ignorados. No los
  añadas a Git para conseguir un estado aparentemente limpio.
- El servidor activo es propio, en Ubuntu. Conserva los contratos de importación
  necesarios hasta sustituir y verificar todos sus consumidores; un nombre
  heredado no prueba que se utilice un backend externo.

## Evidencia y cobertura

La cobertura versionada está en `docs/oficial/review-coverage.json`.
`pending`, `partial` y `code-read` describen lectura de código a una huella concreta.
`code-read` no afirma que todos los recorridos estén validados ni que no queden
correcciones. No marques archivos revisados por haberlos inventariado, buscado
con una expresión o compilado.

Actualiza las huellas con `tools/audit/update_review_coverage.py`; declara como
leídos únicamente los archivos examinados realmente. Los cambios posteriores
invalidan automáticamente esa lectura. Mantén separados los resultados de
pruebas automatizadas, los ensayos de dispositivo y los despliegues efectivos.
No declares el cierre integral mientras queden recorridos o defectos revisables.

Al entregar una APK nueva, identifica su versión y las correcciones que incluye.
La última entrega histórica no contiene automáticamente los commits siguientes.
