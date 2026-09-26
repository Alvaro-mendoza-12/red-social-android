# Gobierno y organigrama de NoWi

## Gobierno inicial

El fundador actúa como Dirección General y propietario del producto. Aprueba publicación en tiendas, cambios irreversibles, gasto, contratos, tratamiento de datos, políticas y riesgos residuales. Las decisiones relevantes se registran como ADR o acta breve con fecha, alternativas, decisión y responsable.

## Organigrama objetivo

```text
Dirección General
├─ Producto y Diseño
├─ Tecnología
│  ├─ Android
│  ├─ Backend y Web
│  ├─ Plataforma / SRE
│  ├─ Datos e IA
│  ├─ Seguridad
│  └─ QA y Release
├─ Confianza, Seguridad y Privacidad
├─ Operaciones, Soporte y Comunidad
├─ Crecimiento y Comercial
├─ Finanzas y Administración
└─ Personas
```

## Responsabilidades mínimas

### Dirección General

- Define misión, mercado, prioridades y tolerancia al riesgo.
- Aprueba releases productivos, presupuestos, proveedores y políticas.
- Nombra propietarios y resuelve conflictos entre áreas.

### Producto y Diseño

- Mantiene problemas de usuario, roadmap, criterios de aceptación y experimentos.
- Revisa experiencia, accesibilidad, consistencia, privacidad por diseño y métricas.
- No declara éxito sin telemetría y validación con dispositivos reales.

### Tecnología

- Mantiene arquitectura, código, pruebas, despliegues, disponibilidad y deuda técnica.
- Separa desarrollo, revisión, release y operación mediante listas de control y evidencia.
- Seguridad y SRE pueden detener un release ante un riesgo crítico no mitigado.

### Confianza, Seguridad y Privacidad

- Define moderación, reportes, apelaciones, protección de menores, retención y respuesta a solicitudes de datos.
- Revisa funciones de descarga, mensajería, contenidos públicos y algoritmos antes de producción.

### Operaciones, Soporte y Comunidad

- Recibe incidentes y solicitudes, clasifica prioridad, comunica estado y conserva trazabilidad.
- Mantiene inventario del servidor, discos, copias, dominios, certificados y proveedores.

### Crecimiento y Comercial

- Opera adquisición y retención sin patrones engañosos ni uso de datos no autorizado.
- Toda campaña tiene objetivo, presupuesto, propietario, medición y criterio de cierre.

### Finanzas, Administración y Personas

- Mantienen segregación básica de gasto, inventario, contratos, accesos y altas/bajas.
- Ninguna contratación obtiene acceso productivo antes de aceptar políticas y completar onboarding.

## Decisiones que nunca se delegan automáticamente

- Constitución jurídica o uso de “SAC”.
- Publicación en Play Store o cambios de firma.
- Eliminación masiva de datos o copias útiles.
- Gasto, contratación, sanciones, términos legales o respuesta a autoridades.
- Aceptación de un riesgo crítico de seguridad, privacidad o disponibilidad.
