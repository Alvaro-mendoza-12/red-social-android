import {fireEvent,render,screen} from '@testing-library/react'
import {AppErrorBoundary} from '../../components/AppErrorBoundary'
import {setChunkReloadGuard} from '../../core/chunkRecovery'
function Broken():never{throw new Error('Failed to fetch dynamically imported module: https://example.test/js/Old.js')}
test('a failed route keeps sibling session controls mounted and presents a readable recovery action',()=>{
 const logger=jest.spyOn(console,'error').mockImplementation(()=>{});setChunkReloadGuard(()=>true)
 try{
  render(<><button>Sesión activa</button><AppErrorBoundary><Broken/></AppErrorBoundary></>)
  expect(screen.getByRole('button',{name:'Sesión activa'})).toBeVisible()
  expect(screen.getByRole('button',{name:'Recargar aplicación'})).toBeVisible()
  expect(screen.queryByText(/https:\/\/example/)).toBeNull()
 }finally{logger.mockRestore();setChunkReloadGuard(()=>false)}
})

test('a typed service interruption shows its character and lets the route recover', () => {
  const logger = jest.spyOn(console, 'error').mockImplementation(() => {})
  let unavailable = true
  function Page() {
    if (unavailable) throw Object.assign(new Error('https://internal.invalid/trace'), { status: 503 })
    return <h2>La sección volvió</h2>
  }
  try {
    render(<AppErrorBoundary><Page /></AppErrorBoundary>)
    expect(screen.getByRole('heading', { name: 'Servicio no disponible' })).toBeVisible()
    expect(document.querySelector('img[src="/error-characters/sleeper.webp"]')).not.toBeNull()
    expect(screen.queryByText(/internal.invalid/)).toBeNull()
    unavailable = false
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(screen.getByRole('heading', { name: 'La sección volvió' })).toBeVisible()
  } finally { logger.mockRestore() }
})
