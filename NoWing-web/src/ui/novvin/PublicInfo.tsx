export const publicInfoPages = {
  '/novvin-web': {
    title: 'Novvin Web: mensajes, videos y momentos desde tu navegador',
    description: 'Usa Novvin desde una computadora, tablet o celular. Conoce los mensajes con archivos, los videos, Social y el acceso con tu cuenta móvil.',
    heading: 'Tu cuenta de Novvin, también en la web.',
    sections: [
      { heading: 'Conversa desde tu computadora', body: 'Abre tus conversaciones, grupos y comunidades con la misma cuenta que usas en el teléfono. En una pantalla grande puedes ver la lista de mensajes junto al chat. En una pantalla pequeña, cada conversación ocupa su propia vista.' },
      { heading: 'Comparte fotos y archivos', body: 'El botón de adjuntar permite elegir imágenes, videos, audios y documentos de tu equipo. Puedes revisar los archivos y añadir un texto antes de enviarlos. Las imágenes se abren en un visor con ampliación y los documentos compatibles permiten vista previa o descarga.' },
      { heading: 'Descubre videos y publicaciones', body: 'Explora videos verticales, sigue a sus creadores y comparte contenido con tus conversaciones. Social reúne publicaciones, comentarios y momentos. Los perfiles conservan el nombre, la foto, las relaciones y las preferencias de la cuenta.' },
      { heading: 'Entra con contraseña o con QR', body: 'Puedes escribir tus datos de acceso o vincular el navegador escaneando un código desde Novvin en tu teléfono. Confirma en el móvil qué navegador estás autorizando. El código es temporal y puedes solicitar otro si vence.' },
    ],
  },
  '/ayuda/vincular-web': {
    title: 'Cómo entrar a Novvin Web con el QR de tu teléfono',
    description: 'Guía para vincular Novvin Web: abre el código en el navegador, escanéalo desde Perfil y confirma el acceso en tu móvil.',
    heading: 'Cómo vincular Novvin Web con tu teléfono.',
    sections: [
      { heading: '1. Abre el código en la web', body: 'Entra a Novvin Web y elige «Iniciar sesión con QR». Mantén abierto el código en la pantalla de la computadora o tablet mientras preparas el teléfono.' },
      { heading: '2. Escanea desde Novvin', body: 'En tu teléfono, abre Novvin con la cuenta que quieres usar. Entra a Perfil y toca «Vincular web». Permite el acceso a la cámara si el teléfono lo solicita y apunta al código completo, incluyendo su borde blanco.' },
      { heading: '3. Confirma el navegador', body: 'Después del escaneo, revisa la cuenta elegida y la solicitud de acceso que aparece en el móvil. Confirma solo el navegador que estás usando. La web abrirá tus mensajes cuando reciba la aprobación.' },
      { heading: 'Si el código no se reconoce', body: 'Sube un poco el brillo de la pantalla, limpia la cámara y aleja el teléfono hasta que se vea todo el código. Comprueba que la cámara tenga permiso en los ajustes del teléfono. Si el código venció, pulsa «Generar otro QR» en la web y escanea el nuevo.' },
      { heading: 'Si escanea pero no abre la sesión', body: 'Mantén conexión a internet en ambos dispositivos y confirma la solicitud en el móvil. Si cambiaste de cuenta, revisa cuál aprobará el acceso. También puedes entrar con contraseña mientras actualizas la aplicación o vuelves a intentar la vinculación.' },
    ],
  },
} as const

export function publicInfoPage(path: string) {
  return publicInfoPages[path as keyof typeof publicInfoPages]
}

export function PublicInfo({ path }: { path: string }) {
  const page = publicInfoPage(path)
  if (!page) return null
  return <div className="public-home">
    <header className="public-home-header"><a className="public-home-brand" href="/"><img src="/novvin-logo.png" width="44" height="44" alt="" />Novvin</a><a className="ghost-btn" href="/login">Iniciar sesión</a></header>
    <main className="public-info">
      <a className="public-info-back" href="/">← Novvin Web</a>
      <h1>{page.heading}</h1>
      <p className="public-info-intro">{page.description}</p>
      <div className="public-home-actions"><a className="primary-btn" href="/login">Entrar a Novvin</a><a className="ghost-btn" href="/explore/videos">Explorar videos</a></div>
      {page.sections.map(section => <section key={section.heading}><h2>{section.heading}</h2><p>{section.body}</p></section>)}
      <nav aria-label="Guías de Novvin"><a href="/novvin-web">Conoce Novvin Web</a><a href="/ayuda/vincular-web">Ayuda para vincular con QR</a><a href="/register">Crear una cuenta</a></nav>
    </main>
    <footer className="public-home-footer"><a className="public-home-brand" href="/">Novvin</a><span>Conversa. Comparte. Descubre.</span><a href="/login">Entrar a mi cuenta</a></footer>
  </div>
}
