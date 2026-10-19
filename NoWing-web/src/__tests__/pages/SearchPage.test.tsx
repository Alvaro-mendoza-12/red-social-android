import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SearchPage } from '../../features/search/SearchPage'
import { searchFallbackProfiles } from '../../core/fallbackData'
jest.mock('../../core/auth/AuthProvider',()=>({useAuth:()=>({user:{id:'me'},isFallbackSession:true})}))
jest.mock('../../core/ubuntuBackend',()=>({shouldUseUbuntuBackend:()=>true}))
jest.mock('../../core/fallbackData',()=>({searchFallbackProfiles:jest.fn(),submitFallbackUserReport:jest.fn()}))
jest.mock('../../core/supabase',()=>({supabase:null,resolveSupabasePublicUrl:(_b:string,url:string)=>url}))
jest.mock('../../components/SafeAvatar',()=>({SafeAvatar:()=>null}))
const search=jest.mocked(searchFallbackProfiles)

test('report options stay collapsed until the user requests them',async()=>{
 search.mockResolvedValue({items:[{id:'stranger',displayName:'Otra persona',username:'otra'}]})
 render(<MemoryRouter><SearchPage/></MemoryRouter>)
 await screen.findByRole('heading',{name:'Otra persona'})
 expect(screen.queryByLabelText('Motivo del reporte')).toBeNull()
 fireEvent.click(screen.getByRole('button',{name:'Reportar a Otra persona'}))
 expect(screen.getByRole('combobox',{name:'Motivo del reporte'})).toBeVisible()
 fireEvent.click(screen.getByRole('button',{name:'Reportar a Otra persona'}))
 expect(screen.queryByLabelText('Motivo del reporte')).toBeNull()
})
test('queries the whole server directory and preserves the input while loading accented names',async()=>{
 search.mockResolvedValue({items:[]})
 render(<MemoryRouter><SearchPage/></MemoryRouter>)
 const input=screen.getByPlaceholderText('Buscar por nombre, usuario o bio')
 await waitFor(()=>expect(search).toHaveBeenCalledWith('',40))
 search.mockResolvedValue({items:[{id:'stranger',displayName:'Álvaro',username:'alvaro'}]})
 input.focus();fireEvent.change(input,{target:{value:'alvaro'}})
 await waitFor(()=>expect(search).toHaveBeenCalledWith('alvaro',40))
 expect(await screen.findByRole('heading',{name:'Álvaro'})).toBeTruthy()
 expect(screen.getByPlaceholderText('Buscar por nombre, usuario o bio')).toBe(input)
 expect(document.activeElement).toBe(input)
 expect(screen.getByRole('link',{name:'Ver perfil de Álvaro'})).toHaveAttribute('href','/app/profile/stranger')
})
