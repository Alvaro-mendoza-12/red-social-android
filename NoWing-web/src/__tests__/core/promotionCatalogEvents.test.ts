import {notifyPromotionCatalogChanged,promotionCatalogEvent} from '../../core/promotionCatalogEvents'

test('promotion changes carry only the owning account for scoped feed refresh',()=>{
 const event=jest.fn()
 window.addEventListener(promotionCatalogEvent,event)
 try{
  notifyPromotionCatalogChanged('account-a')
  expect(event).toHaveBeenCalledTimes(1)
  expect((event.mock.calls[0][0] as CustomEvent).detail).toBe('account-a')
 }finally{window.removeEventListener(promotionCatalogEvent,event)}
})
