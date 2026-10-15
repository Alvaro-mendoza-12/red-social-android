import { chatGallery } from '../../core/chatGallery'
const message=(id:string,contentType='image')=>({id,contentType,content:'',mediaUrl:'https://media.test/photo.png',deletedForAll:false,deletedBy:[]})
test('includes photos across text and date boundaries in conversation order',()=>{
  expect(chatGallery([message('one'),message('text','text'),message('two'),message('clip','video')],'me').map(item=>item.id)).toEqual(['one','two','clip'])
})
test('does not expose stickers, view-once media or deleted photos',()=>{
  expect(chatGallery([message('sticker','sticker'),message('once','view_once_image'),{...message('deleted'),deletedForAll:true},{...message('hidden'),deletedBy:['me']},message('visible')],'me').map(item=>item.id)).toEqual(['visible'])
})
