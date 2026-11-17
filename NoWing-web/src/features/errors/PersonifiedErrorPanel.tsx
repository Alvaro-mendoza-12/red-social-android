import type { ReactNode } from 'react'
import { characters, type ErrorCharacter, type NovvinProblem } from './errorCatalog'
import { useErrorSpeech } from './useErrorSpeech'
import './personifiedErrors.css'

const levelLabel = { notice: 'Aviso', connection: 'Conexión', critical: 'Error crítico' }

export function ErrorBrand() {
  return <a href="/" className="novvin-error-brand" aria-label="NOVVIN, inicio">
    <img src="/novvin-logo.png" width={39} height={39} alt="" />
    <span>NOVVIN</span>
  </a>
}

export function ErrorPortrait({ character, animate = false }: { character: ErrorCharacter; animate?: boolean }) {
  return <span aria-hidden="true" className="novvin-error-portrait" data-character={character} data-animate={animate}>
    <img src={`/error-characters/${character}.webp`} alt="" width={192} height={160} decoding="async" />
    {animate && <span className="novvin-error-symbol">{characters[character].symbol}</span>}
  </span>
}

type ErrorPanelProps = {
  problem: NovvinProblem
  actions?: ReactNode
  heading?: 'h1' | 'h2'
  live?: boolean
}

export function PersonifiedErrorPanel({ problem, actions, heading: Heading = 'h2', live = true }: ErrorPanelProps) {
  const { listen, stop, speaking, unavailable } = useErrorSpeech(problem)
  return <section className="novvin-error-panel" data-character={problem.character}>
    <ErrorPortrait key={problem.character} character={problem.character} animate />
    <div className="novvin-error-detail">
      <div role={live ? (problem.level === 'critical' ? 'alert' : 'status') : undefined}>
        <div className="novvin-error-meta">
          {problem.code !== null && <span className="novvin-error-code">{problem.code}</span>}
          <span>{levelLabel[problem.level]}</span>
        </div>
        <Heading>{problem.title}</Heading>
        <p>{problem.description}</p>
      </div>
      <div className="novvin-error-controls">
        <button className="novvin-error-button solid" type="button" onClick={listen} disabled={speaking}>
          Escuchar
        </button>
        <button className="novvin-error-button" type="button" onClick={stop} disabled={!speaking}>
          Detener
        </button>
      </div>
      {unavailable && <p className="novvin-error-voice" role="status">La voz en español no está disponible en este navegador.</p>}
      {actions && <div className="novvin-error-controls novvin-error-actions">{actions}</div>}
    </div>
  </section>
}

export function ErrorHomeLink() {
  return <a className="novvin-error-button" href="/">Ir al inicio</a>
}
