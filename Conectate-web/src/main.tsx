import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import './styles/app-parity.css'
import App from './App.tsx'
import { AppErrorBoundary } from './components/AppErrorBoundary.tsx'
import { initializeRuntimeBackend } from './core/runtimeBackend.ts'

const root = createRoot(document.getElementById('root')!)
let appRendered = false
const renderApp = () => {
  if (appRendered) return
  appRendered = true
  root.render(
    <StrictMode>
      <AppErrorBoundary>
        <App />
      </AppErrorBoundary>
    </StrictMode>,
  )
}

root.render(<div role="status" aria-live="polite">Conectando con Novvin…</div>)
const startupDeadline = window.setTimeout(renderApp, 4_000)
void initializeRuntimeBackend().catch(() => {}).finally(() => {
  window.clearTimeout(startupDeadline)
  renderApp()
})
