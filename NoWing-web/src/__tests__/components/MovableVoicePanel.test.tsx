import {fireEvent,render,screen} from '@testing-library/react'
import {constrainPanel} from '../../core/floatingPanel'
import {MovableVoicePanel} from '../../components/MovableVoicePanel'
test('constrains the panel after dragging and viewport changes',()=>{
 expect(constrainPanel({x:2000,y:-20},300,60,390,844)).toEqual({x:82,y:8})
 expect(constrainPanel({x:-90,y:900},300,60,320,844)).toEqual({x:8,y:776})
})
test('positions can be selected with a click as an alternative to dragging',()=>{
 render(<MovableVoicePanel accountId="example"><button>Micrófono</button></MovableVoicePanel>)
 fireEvent.click(screen.getByRole('button',{name:'Mover indicador de sala'}));expect(screen.getByRole('button',{name:'Arriba izquierda'})).toBeVisible()
 fireEvent.click(screen.getByRole('button',{name:'Arriba izquierda'}));expect(screen.getByRole('complementary',{name:'Sala de voz activa'})).toHaveStyle({left:'8px',top:'8px'})
})
