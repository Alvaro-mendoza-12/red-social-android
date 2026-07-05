# 08 — Pruebas, release y handoff

Este documento define cómo probar, publicar y entregar el proyecto a otro equipo o IA.

## Estados permitidos

Usar siempre una de estas etiquetas:

- **Implementado**: el código existe.
- **Compila**: pasó build local.
- **Probado local**: probado en emulador/local.
- **Probado Android real**: probado en teléfono real.
- **Probado redes distintas**: probado con Wi-Fi/datos u otras redes.
- **Producción observado**: usuarios reales lo probaron.
- **Pendiente**: no validado aún.

No usar “listo” sin decir en qué nivel.

## Checklist antes de tocar producción

1. Revisar estado Git:

```powershell
git status --short
```

2. Crear backup si se toca backend/datos:

```bash
cd ~/conectate-local-fallback
./scripts/backup-ubuntu-primary.sh
```

3. Verificar backup:

```bash
./scripts/verify-backup.sh
```

4. Ver estado servidor:

```bash
~/bin/conectate-status
```

## Pruebas Android mínimas

### Auth

- correo inexistente;
- correo Supabase migrado;
- correo local correcto;
- contraseña incorrecta;
- logout/login;
- token expirado.

### Mensajes

- enviar 1 a 1;
- recibir 1 a 1;
- último mensaje cambia;
- contador sube;
- contador baja al abrir;
- notificación desaparece al leer;
- fijar chat persiste;
- archivar persiste;
- favorito filtra;
- eliminar/vaciar chat no reaparece si esa es la regla funcional.

### Grupos

- crear grupo;
- agregar usuario;
- ver participantes;
- enviar mensaje;
- contador por grupo;
- llamar grupo;
- salir/eliminar según permisos.

### Comunidades

- explorar comunidades;
- mis comunidades;
- unirse;
- contador;
- roles;
- admin-only messages;
- expulsar;
- promover/degradar admin;
- editar info.

### Videos

- feed carga;
- autoplay;
- pausa/reanuda;
- scroll fluido;
- barra de progreso;
- video lento muestra loading;
- subir video;
- recortar si excede límite;
- thumbnails.

### Perfil

- abrir perfil propio;
- abrir perfil ajeno;
- foto carga rápido;
- cache funciona;
- editar perfil;
- foto/video corto si se implementa.

### Llamadas y salas

Probar con dos Android reales:

- misma Wi-Fi;
- Wi-Fi + datos móviles;
- llamada saliente;
- llamada entrante;
- aceptar;
- rechazar;
- colgar;
- sala de voz;
- micrófono;
- cámara;
- pantalla;
- reacciones;
- chat de sala;
- reconexión.

## Build release

Desde Windows:

```powershell
Set-Location "D:\red social - copia"
.\gradlew.bat :app:compileReleaseKotlin
.\gradlew.bat :app:bundleRelease
```

AAB:

```text
app/build/outputs/bundle/release/app-release.aab
```

Antes de subir:

- confirmar `versionCode` mayor que Play Store;
- confirmar endpoint/discovery;
- confirmar firma release;
- instalar en dispositivo real;
- revisar crash al abrir.

## Handoff para equipo o IA

Antes de pedir cambios, entregar:

- esta carpeta `docs/oficial/`;
- salida reciente de `~/bin/conectate-status`;
- versión actual de Play Store;
- `versionCode` actual;
- endpoint discovery actual;
- descripción exacta del bug;
- pasos para reproducir;
- capturas;
- logs si existen.

Prompt recomendado:

```text
Lee primero docs/oficial/README.md y sigue el índice.
No asumas estado: valida con código y comandos.
No toques producción sin backup.
No declares listo WebRTC sin prueba en Android real y redes distintas.
Mantén Supabase solo como legado.
```

## Qué revisar si una IA anterior hizo cambios

- ¿Cambió endpoints a URL temporal?
- ¿Subió secretos?
- ¿Rompió discovery?
- ¿Tocó auth migration?
- ¿Modificó schema sin migración?
- ¿Cambió WebRTC solo en UI sin signaling/TURN?
- ¿Subió AAB con `versionCode` correcto?
- ¿Documentó pruebas reales?

## Reporte final esperado de cualquier cambio

Debe incluir:

```text
Cambios:
- ...

Archivos tocados:
- ...

Pruebas:
- ...

No probado:
- ...

Riesgos:
- ...

Rollback:
- ...
```

## Priorización de bugs

P0 — Servicio caído:

- app no abre;
- backend no responde;
- login roto para todos;
- base de datos caída;
- URL pública rota.

P1 — Producción crítica:

- llamadas/salas crashean;
- mensajes no llegan;
- notificaciones rotas;
- pérdida de datos;
- auth muestra flujo incorrecto.

P2 — Experiencia importante:

- contadores incorrectos;
- perfil lento;
- videos lentos;
- stickers mal dimensionados;
- filtros no hacen nada.

P3 — Mejora:

- UI polish;
- animaciones;
- documentación;
- optimización secundaria.

