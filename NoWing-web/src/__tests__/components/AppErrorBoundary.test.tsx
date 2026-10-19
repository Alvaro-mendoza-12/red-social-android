import {render,screen} from '@testing-library/react'
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
