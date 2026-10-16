import { act, fireEvent, render, screen } from '@testing-library/react'
import { SystemAnnouncementHost } from '../../components/SystemAnnouncementHost'
import { loadSystemAnnouncements } from '../../core/systemAnnouncements'
jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'qa-owner' } }) }))
jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true }))
jest.mock('../../components/systemAnnouncements.css', () => ({}))
jest.mock('../../core/systemAnnouncements', () => ({
 loadSystemAnnouncements: jest.fn(), activeAnnouncement: () => true, announcementSchedule: () => '', officialUpdateUrl: 'https://play.google.com/store/apps/details?id=conectate.com',
}))
const notice = {id:'qa-notice',kind:'update',title:'Actualización de prueba',body:'Mensaje de prueba',expiresAt:'2099-01-01'}
beforeEach(() => { localStorage.clear();jest.mocked(loadSystemAnnouncements).mockReset().mockResolvedValue([notice as never]) })
afterEach(() => { jest.restoreAllMocks() })
test('reserves measured space and restores it on resize, dismiss and unmount', async () => {
 let height=64
 jest.spyOn(HTMLElement.prototype,'getBoundingClientRect').mockImplementation(() => ({height,width:600,top:0,bottom:height,left:0,right:600,x:0,y:0,toJSON:()=>({})}))
 const view=render(<SystemAnnouncementHost />)
 await screen.findByRole('region',{name:'Aviso de Novvin'})
 expect(document.documentElement.style.getPropertyValue('--novvin-system-notice-height')).toBe('64px')
 height=96;act(()=>window.dispatchEvent(new Event('resize')))
 expect(document.documentElement.style.getPropertyValue('--novvin-system-notice-height')).toBe('96px')
 fireEvent.click(screen.getByRole('button',{name:'Más tarde'}))
 expect(screen.queryByRole('region')).not.toBeInTheDocument()
 expect(document.documentElement.style.getPropertyValue('--novvin-system-notice-height')).toBe('0px')
 view.unmount();expect(document.documentElement.style.getPropertyValue('--novvin-system-notice-height')).toBe('')
})
test('dismissal in another tab removes the notice in this tab', async () => {
 render(<SystemAnnouncementHost />);await screen.findByRole('region',{name:'Aviso de Novvin'})
 act(()=>window.dispatchEvent(new StorageEvent('storage',{key:'novvin:system-notices:seen:qa-owner',newValue:JSON.stringify(['qa-notice'])})))
 expect(screen.queryByRole('region')).not.toBeInTheDocument()
})
test('focus cycles reuse a recent successful response and refresh after one minute', async () => {
 const clock=jest.spyOn(Date,'now').mockReturnValue(100000)
 render(<SystemAnnouncementHost />);await screen.findByRole('region',{name:'Aviso de Novvin'})
 await act(async()=>{window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'))})
 expect(loadSystemAnnouncements).toHaveBeenCalledTimes(1)
 clock.mockReturnValue(160001);await act(async()=>window.dispatchEvent(new Event('focus')))
 expect(loadSystemAnnouncements).toHaveBeenCalledTimes(2)
})
