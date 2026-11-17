import { createRoot, hydrateRoot } from 'react-dom/client'
import { NotFoundPage } from '../../ui/novvin/NotFoundPage'

const container = document.getElementById('root')
if (container) {
  const page = <NotFoundPage />
  if (container.hasChildNodes()) hydrateRoot(container, page)
  else createRoot(container).render(page)
}
