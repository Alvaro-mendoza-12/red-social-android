import {act,render} from '@testing-library/react'
import {MemoryRouter} from 'react-router-dom'
import {PageMetadata} from '../../ui/novvin/PageMetadata'
import {applyInboxUnreadSnapshot,confirmInboxConversationRead,markInboxConversationRead} from '../../core/inboxActivity'
let mockMetadataUserId='title-owner'
jest.mock('../../core/auth/AuthProvider',()=>({useAuth:()=>({user:mockMetadataUserId?{id:mockMetadataUserId}:null})}))
beforeEach(()=>{mockMetadataUserId='title-owner'})
test('tab title follows the shared unread count and clears on confirmed read',()=>{
 render(<MemoryRouter initialEntries={['/app/messages']}><PageMetadata/></MemoryRouter>)
 expect(document.title).toBe('Mensajes · Novvin')
 act(()=>applyInboxUnreadSnapshot('title-owner','chat',[{id:'peer',unreadCount:3,updatedAt:'2026-10-19T07:00:00Z'}],100))
 expect(document.title).toBe('(3) Mensajes · Novvin')
 act(()=>{const token=markInboxConversationRead('title-owner','chat','peer');confirmInboxConversationRead('title-owner','chat','peer',token)})
 expect(document.title).toBe('Mensajes · Novvin')
})
test('signing out removes the private unread count from the tab',()=>{
 mockMetadataUserId='title-logout-owner'
 applyInboxUnreadSnapshot(mockMetadataUserId,'chat',[{id:'peer',unreadCount:2,updatedAt:'2026-10-19T07:00:00Z'}],100)
 const {rerender}=render(<MemoryRouter initialEntries={['/app/messages']}><PageMetadata/></MemoryRouter>)
 expect(document.title).toBe('(2) Mensajes · Novvin')
 mockMetadataUserId=''
 rerender(<MemoryRouter initialEntries={['/app/messages']}><PageMetadata/></MemoryRouter>)
 expect(document.title).toBe('Mensajes · Novvin')
})
