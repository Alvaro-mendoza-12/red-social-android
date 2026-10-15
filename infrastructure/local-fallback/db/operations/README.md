# Rol PostgreSQL de ejecución de la API

## Estado y propósito

El candidato obliga a usar un rol sin superuser, createdb, createrole, replication ni bypassrls. También rechaza membresías de roles administrativos y roles que permiten leer/escribir archivos o ejecutar programas en PostgreSQL. La comprobación ocurre antes del DDL de arranque en producción y deniega el inicio si no puede verificar los permisos.

**Esta operación todavía no está ejecutada en el servidor.** El rol `conectate` sigue como administrador de bootstrap; la API pasa a `novvin_api`. El bootstrap SQL se ejecuta solamente con el administrador y contra la base dedicada `conectate_fallback`.

## Preparación y ejecución durante el despliegue coordinado

1. Tener un backup restaurable de la base y conservar la imagen, configuración y versión actuales. Ensayar primero en staging.
2. Aplicar el init y las migraciones históricas con el administrador. La nueva migración `001_runtime_prerequisites.sql` permite completar las migraciones antes de iniciar la API.
3. Ejecutar `db/operations/10-bootstrap-runtime-role.sql` con `psql -v ON_ERROR_STOP=1`, autenticado como el administrador y conectado a `conectate_fallback`. El script crea el rol y transfiere los objetos de aplicación del schema `public`; excluye objetos de extensiones. Sus tablas y funciones pueden contener datos privados: no imprimirlos.
4. Instalar `15-runtime-import-read.sql` con el administrador antes de inicializar las tablas públicas con el runtime sin privilegios administrativos. Después de esa inicialización, instalar `20-storage-read-legacy.sql`, `22-storage-import-trigger-guard.sql` y después `16-runtime-import-mutations.sql` con el administrador. Este es el orden usado por la prueba HTTP sobre la estructura importada observada. La operación 22 reemplaza la cadena de triggers importados por funciones protegidas que escriben solo bindings e historial. La 16 instala capacidades limitadas y no concede DML a `novvin_api`. Revisar todas las tablas importadas y completar aceptación HTTP en staging antes de cambiar el rol. Repetir siempre 22 después de 20: la versión histórica de 20 reinstala triggers públicos que 16 rechaza.
5. Fijar la contraseña con el comando interactivo de psql `\password novvin_api`. No poner la contraseña en argumentos del proceso ni en este documento.
6. Añadir a la configuración privada `FALLBACK_API_DATABASE_URL` con una URL PostgreSQL del rol `novvin_api`, host `db`, puerto `5432`, base `conectate_fallback`. Codificar los caracteres reservados de la contraseña para una URL. Proteger el archivo de configuración y conservarlo fuera de Git.
7. Actualizar conjuntamente API, señalización y clientes: el candidato de llamadas requiere preflight persistido. No mezclar una señalización con autorización nueva y clientes que todavía omitan ese paso.
8. Comprobar health/readiness, flags del rol real, arranque, login, registro, mensajes, subida, encuestas, sorteos y jobs. Verificar que crear un rol, crear una base o leer un archivo del servidor devuelve denegación de permisos.

La plantilla `config.example` deja vacía la URL del runtime. Docker Compose requiere configurarla explícitamente; la API rechaza usar nuevamente el superusuario en producción.

## Evidencia local

En PostgreSQL 18 aislado se verificó init + 37 migraciones, bootstrap repetido, arranque de API con `novvin_api`, 17 casos HTTP de Botella al mar y siete comprobaciones de permisos/guard. En PostgreSQL 16 también se aprobaron 38 casos de permisos, Botella al mar y autorización HTTP de llamadas, con bootstrap repetido y API real. Las operaciones administrativas se rechazaron con SQLSTATE `42501`. La política rechazó tanto un superusuario directo como una membresía administrativa; al retirar esa membresía volvió a aceptar el rol restringido. Estas pruebas locales no ejecutan los medios WebRTC ni sustituyen la aceptación de otros módulos o del servidor desplegado.

La comprobación aislada posterior en PostgreSQL 16 reprodujo las 54 tablas y 677 columnas importadas observadas y aprobó 49 casos HTTP/permisos, con datos sintéticos. Incluyó tres comprobaciones de dump/restore dentro del mismo cluster de prueba, con roles ya existentes. No leyó filas de usuarios de producción y no acredita una restauración del servidor ni de archivos multimedia. Evidencia local: `D:\red social - copia\audit\2026-10-07-integral\security-real-schema\postgres16-http-latest.json`.

## Límites y recuperación

El runtime aún posee los objetos de aplicación y puede ejecutar DDL sobre ellos porque el servidor crea/actualiza schema al iniciar. Separar todo el DDL a un migrador y usar un rol de runtime con permisos DML es una mejora posterior medible. Este control no sustituye la autorización de endpoints ni demuestra cobertura RLS.

Si falla la aceptación, detener el despliegue y restaurar la imagen y configuración verificadas. El administrador anterior no fue eliminado ni degradado; conserva acceso para backup/restore y las operaciones de recuperación. No transferir objetos de otras bases ni ejecutar el bootstrap contra un schema compartido con otra aplicación.

## Datos importados y entrega privada: gate pendiente

La operación administrativa `15-runtime-import-read.sql` concede USAGE y SELECT a las tablas existentes dentro de una allowlist explícita de 74 nombres; no concede escritura ni propiedad en `supabase_import`. La observación de metadatos del servidor encontró 54 tablas importadas: 74 describe el contrato admitido, no la cantidad de tablas presentes ni una aceptación de todas ellas. Repetirla después de importaciones administrativas que reemplacen tablas.

La operación `16-runtime-import-mutations.sql` instala cinco capacidades para editar contenido/visibilidad de posts, editar contenido de comentarios, eliminar posts y comentarios, sustituir votos y purgar filas relacionadas con una identidad. El propietario de las funciones es un rol NOLOGIN sin capacidades administrativas ni membresía para `novvin_api`; no posee las tablas ni puede crear objetos importados. Las funciones están en un schema administrativo separado de `public`, con `search_path` fijo, identificadores validados, parámetros, allowlist explícita, claves de parche restringidas y coincidencias exactas de arrays. No conceden INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ni propiedad de las tablas importadas al runtime. La autorización del usuario sigue en cada endpoint: una capacidad SQL no verifica un JWT.

Repetir 22, 15 y 16 después de importaciones administrativas que recrean tablas. La operación 16 y la purga rechazan una tabla con campos de identidad fuera de la allowlist para impedir una limpieza silenciosamente incompleta. Todas las capacidades verifican antes del DML la cadena de triggers y las dependencias de expresiones; se rechazan callbacks públicos propiedad del runtime, reglas, tipos personalizados, RLS y particiones sin un contrato revisado. Las funciones protegidas usan un segundo rol NOLOGIN sin DML importado ni membresías, de modo que un callback añadido a una tabla pública no recibe los permisos del operador importado. Revisar y ampliar el contrato antes de importar tablas nuevas. Las funciones fallan si falta la operación; no existe fallback al SQL directo ni al superusuario. Este cambio requiere pruebas HTTP de propietarios, usuarios ajenos, votos y borrado de cuenta sobre staging además de las pruebas locales de PostgreSQL. La propuesta evita la reescritura simultánea de más de 40 lecturas SQL; migrar el archivo histórico a overrides/tombstones continúa como una evolución de arquitectura separada.

La operación `20-storage-read-legacy.sql` instala funciones, índices y triggers administrativos para referencias privadas importadas. Requiere las tablas públicas inicializadas, el bootstrap 10 y la base dedicada; no se ejecuta automáticamente como runtime. Después, el inicializador completa el backfill con los orígenes HTTPS configurados explícitamente.

`STORAGE_READ_ENFORCE` permanece desactivado por defecto. Su activación requiere `STORAGE_READ_ORIGINS` y `PUBLIC_BASE_URL` HTTPS coherentes, políticas completas para archivos sociales públicos, migración de URLs históricas, vista única con lease atómico y clientes Android/web aceptados. Las pruebas locales de membresía, bloqueos, HEAD, Range, ETag, variantes y headers X-Accel no acreditan Nginx real ni la entrega privada de toda la aplicación.
