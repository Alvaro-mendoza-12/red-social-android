import { fireEvent, render, screen } from '@testing-library/react'
import { MediaDeviceDialog } from '../../components/MediaDeviceDialog'
import { AudioOutputSelect } from '../../components/AudioOutputSelect'
jest.mock('../../components/mediaDeviceDialog.css', () => ({}))
const originalShow = HTMLDialogElement.prototype.showModal, originalClose = HTMLDialogElement.prototype.close
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open','');this.querySelector('button')?.focus() }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterAll(() => { HTMLDialogElement.prototype.showModal=originalShow;HTMLDialogElement.prototype.close=originalClose })
test('opens above the page, handles Escape and restores focus when closed', () => {
  const previous=document.createElement('button');document.body.append(previous);previous.focus();const close=jest.fn()
  const { unmount }=render(<MediaDeviceDialog title="Sonido" label="Ajustes de prueba" onClose={close}>Contenido</MediaDeviceDialog>)
  const dialog=screen.getByRole('dialog',{name:'Ajustes de prueba'});expect(dialog).toHaveAttribute('open')
  fireEvent(dialog,new Event('cancel',{cancelable:true}));expect(close).toHaveBeenCalledTimes(1)
  unmount();expect(document.activeElement).toBe(previous);previous.remove()
})
test('exposes a stable accessible label and forwards the selected output', () => {
  Object.defineProperty(HTMLMediaElement.prototype,'setSinkId',{configurable:true,value:jest.fn()})
  const change=jest.fn();render(<AudioOutputSelect devices={[{deviceId:'headset',kind:'audiooutput',label:'Auriculares'} as MediaDeviceInfo]} value="" onChange={change}/>)
  fireEvent.change(screen.getByRole('combobox',{name:'Salida de audio',exact:true}),{target:{value:'headset'}})
  expect(change).toHaveBeenCalledWith('headset');delete (HTMLMediaElement.prototype as unknown as Record<string,unknown>).setSinkId
})
