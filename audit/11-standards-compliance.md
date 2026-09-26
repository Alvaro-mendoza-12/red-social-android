# 11 — Estándares de calidad y seguridad

Fecha: 2026-09-26. Se usan marcos como **guías de cobertura**, sin declaración de certificación, conformidad legal ni cumplimiento completo.

| Marco | Estado comprobado | Brecha para evaluación formal |
|---|---|---|
| [ISO/IEC 25010:2023](https://committee.iso.org/standard/78176.html) | Línea base de pruebas funcionales, inventario de arquitectura y algunas medidas de latencia. | Métricas por característica de calidad, criterios de aceptación y evidencia en producción. |
| [NIST SSDF SP 800-218](https://csrc.nist.gov/pubs/sp/800/218/final) | Quality gate, tests, bloqueo de secretos públicos y audits parciales. | SBOM, revisión de proveedores, trazabilidad de cambios y respuesta documentada a vulnerabilidades. |
| [OWASP MASVS](https://mas.owasp.org/MASVS/) | Manifest, backup y origen HTTPS Android inspeccionados. | Matriz completa por control, análisis APK release y ensayos dinámicos. |
| [OWASP API Security Top 10](https://api-security.owasp.org/editions/2023/en/0xa1-broken-object-level-authorization/) | Tests de autorización de varias rutas sensibles. | Inventario METHOD/PATH y BOLA/BFLA por objeto/función. |
| [WCAG 2.2](https://www.w3.org/TR/wcag/) | Inspección visual parcial de acceso. | Test de teclado, TalkBack, contraste y estados en todas las pantallas web/móvil. |

La meta verificable es convertir cada marco en control, evidencia, excepción y dueño; citar un estándar no hace al producto conforme.
