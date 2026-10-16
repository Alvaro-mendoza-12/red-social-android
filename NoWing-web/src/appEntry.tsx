import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
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

// A configured or cached backend is available synchronously. Discovery and
// readiness reconcile in the background instead of holding the first screen.
renderApp()
void initializeRuntimeBackend().catch(() => {})
