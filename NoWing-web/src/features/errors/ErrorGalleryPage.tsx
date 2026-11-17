import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight } from 'lucide-react'
import { characters, errorCatalog, problemForStatus, type NovvinProblem } from './errorCatalog'
import { ErrorBrand, ErrorPortrait, PersonifiedErrorPanel } from './PersonifiedErrorPanel'

export function ErrorGalleryPage() {
  const initial = problemForStatus(404) ?? errorCatalog[0]
  const [problem, setProblem] = useState(initial)
  const preview = useRef<HTMLDivElement>(null)

  function select(entry: NovvinProblem) {
    setProblem(entry)
    preview.current?.focus({ preventScroll: true })
    preview.current?.scrollIntoView?.({ block: 'start' })
  }

  return <main className="novvin-error-page" lang="es">
    <header className="novvin-error-header">
      <ErrorBrand />
      <Link to="/ayuda" className="novvin-error-button">Centro de ayuda <ArrowRight size={16} aria-hidden="true" /></Link>
    </header>
    <div className="novvin-error-content">
      <div className="novvin-error-intro">
        <h1>Errores con <span>personalidad</span></h1>
        <p>Conoce quién aparece cuando algo falla y qué puedes hacer para continuar.</p>
      </div>
      <div className="novvin-error-preview" ref={preview} tabIndex={-1} aria-label="Vista del error">
        <PersonifiedErrorPanel problem={problem} live={false} />
        <span className="novvin-error-announcement" role="status">{problem.code} · {problem.title}</span>
      </div>
      <section className="novvin-error-gallery" aria-labelledby="error-characters-title">
        <h2 id="error-characters-title">Conoce a los personajes</h2>
        <div className="novvin-error-cards">
          {Object.entries(characters).map(([character, person]) => {
            const entry = problemForStatus(person.primaryCode)
            if (!entry) return null
            return <button type="button" key={character} className="novvin-error-card"
              data-character={character} aria-pressed={entry.character === problem.character}
              onClick={() => select(entry)}>
              <span className="novvin-error-card-code">{person.primaryCode}</span>
              <span className="novvin-error-card-name">{person.name}</span>
              <ErrorPortrait character={entry.character} />
            </button>
          })}
        </div>
      </section>
      <section className="novvin-error-gallery" aria-labelledby="error-codes-title">
        <h2 id="error-codes-title">Códigos de error</h2>
        <div className="novvin-error-controls">
          {errorCatalog.map(entry => <button type="button" key={entry.code} className="novvin-error-button"
            aria-label={`${entry.code} · ${entry.title}`} aria-pressed={problem.code === entry.code}
            onClick={() => select(entry)}>{entry.code}</button>)}
        </div>
      </section>
    </div>
  </main>
}
