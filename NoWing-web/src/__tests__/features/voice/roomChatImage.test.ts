import {boundedRoomChat,firstRoomClipboardImage,validRoomImage} from '../../../features/voice/roomChatImage'
test('session memory is bounded and a duplicate echo does not create another entry',()=>{
 const values=Array.from({length:110},(_,i)=>({id:String(i),text:'Photo',imageData:'data'}))
 const result=boundedRoomChat([...values,values[109]])
 expect(result).toHaveLength(100);expect(result.filter(row=>row.imageData)).toHaveLength(10);expect(result[0].text).toBe('Foto anterior de esta sesión')
 expect(validRoomImage('https://example.org/photo')).toBe(false);expect(validRoomImage('data:image/svg+xml,test')).toBe(false)
})

test('clipboard reads actual image bytes and never treats pasted links or HTML as uploaded photos',()=>{
 const file=new File(['photo'],'clipboard.png',{type:'image/png'})
 expect(firstRoomClipboardImage([{kind:'string',type:'text/plain',getAsFile:()=>null},{kind:'file',type:'image/png',getAsFile:()=>file}])).toBe(file)
 expect(firstRoomClipboardImage([{kind:'string',type:'text/html',getAsFile:()=>null}])).toBeNull()
 expect(firstRoomClipboardImage([{kind:'file',type:'image/svg+xml',getAsFile:()=>file}])).toBeNull()
})

test('different authors may use the same message identifier without erasing each other',()=>{
 const values=[{id:'same',userId:'one',text:'First'},{id:'same',userId:'two',text:'Second'}]
 expect(boundedRoomChat([...values,values[0]])).toHaveLength(2)
})

test('expired text and images are purged, while a server echo updates its pending row',()=>{
 const pending={id:'id',userId:'owner',text:'photo',imageData:'bytes',expiresAt:2000,delivery:'sending'}
 const sent={...pending,delivery:'sent'}
 expect(boundedRoomChat([pending,sent],1500)).toEqual([sent])
 expect(boundedRoomChat([sent],2000)).toEqual([])
})
