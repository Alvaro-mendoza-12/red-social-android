# Contadores de bandeja e iconos — 12 de noviembre de 2026

## Correcciones Android

La bandeja mostraba chips de favoritos y archivados sin contadores y no incluía el filtro «No leídos». Ahora los chips de Todas, No leídos, Favoritos, Archivados y carpetas muestran el número de conversaciones con mensajes pendientes. No cuentan el total de mensajes. Todas/No leídos/Favoritos y las carpetas excluyen archivados; Archivados cuenta sus propios pendientes.

Se calculan desde las instantáneas locales ligadas a la cuenta, usando la misma normalización y corte de ocultación de la lista, antes de buscar o seleccionar filtros. Los grupos quedan fuera de estos contadores privados. Se conserva el cero cuando no hay pendientes y la presentación 99+ para valores grandes. «Gestionar» carpetas usa una carpeta abierta, en lugar de un icono de eliminar.

## Correcciones web

Los cuatro filtros usan la proyección de actividad de la bandeja antes de aplicar la búsqueda. Así un mensaje entrante actualiza favoritos y archivados y leer la conversación limpia su contador. Los símbolos de favorito, fijado, silenciado y archivado se sustituyeron por los iconos de la biblioteca existente, con nombres accesibles. El menú distingue acciones inversas: fijar/desfijar, silenciar/activar, favorito/quitar y archivar/restaurar. Se conservan teclado, foco y los controles independientes para abrir un chat o sus opciones.

El título de crear un espacio corresponde a grupo, comunidad o sala de voz. El estado de conexión de Mensajes presenta un mensaje comprensible sin exponer nombres de implementación ni pedir al visitante modificar archivos de configuración. Se aplicó la skill `frontend-design-review` sobre los patrones y estilos existentes; no se introduce el rediseño experimental 105 ni se afirma una comparación con un Figma inexistente en esta revisión.

## Validación y límites

Android aprobó 25 pruebas JVM en seis clases: cinco nuevas de contadores más recuperación de conversaciones, ocultación, filtros y aislamiento de instantáneas por cuenta. Web aprobó 39 pruebas dirigidas en tres suites, incluida una prueba del componente real con eventos de llegada en favorito y archivado, búsqueda y lectura. La primera ejecución detectó que la versión instalada de Lucide no exporta MailUnread; se sustituyó por Mail antes de aprobar compilación y pruebas.

TypeScript, Vite y los controles de las 21 páginas públicas pasan. ESLint termina con cero errores y siete avisos existentes. El fragmento privado grande sigue pendiente de optimización. La batería web completa aprobó 1.190 pruebas en 186 suites. La publicación se realiza en las dos ramas estables con árboles iguales; se contrasta el despliegue automático de Vercel por separado.

No se generó ni instaló una APK nueva; los paquetes históricos no incluyen automáticamente estas correcciones. No se ensayó este bloque en un teléfono físico ni se afirma validación visual de todos los tamaños, temas o escalas de texto. No se revisó el conjunto completo de iconos de otros módulos. La revisión integral permanece abierta.

## Dominio y correo

La revisión 119 registra la producción de novvinapp.com, Google Search Console, sitemap procesado de 21 páginas, solicitud de indexación e IndexNow 202. La aceptación de rastreo no confirma indexación ni posiciones de búsqueda. El servidor y las rutas de los clientes instalados se conservaron.

El proveedor rechaza la última credencial SMTP corporativa suministrada. Se conservó en privado y se restauró el remitente anterior después de verificar autenticación TLS, para mantener disponible la recuperación de cuentas. No se enviaron correos de prueba ni se cambiaron contraseñas del proveedor. El propietario comunicó que ya configuró la foto; no se pudo verificar en Chrome mientras Windows estaba bloqueado/minimizado. La firma y el avatar en servicios receptores no se dan por configurados.
