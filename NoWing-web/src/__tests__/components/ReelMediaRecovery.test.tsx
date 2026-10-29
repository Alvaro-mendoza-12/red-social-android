import {act,fireEvent,render,screen} from '@testing-library/react'
import {MemoryRouter} from 'react-router-dom'
import {ReelFeedCard} from '../../features/reels/ReelsPage'
import {browserStreamingSource} from '../../core/media/browserStreaming'
import {invalidateStorageReadIdentity,updateStorageReadIdentity} from '../../core/media/storageReadSession'
jest.mock('../../components/ProfileStatusBadges',()=>({ProfileStatusBadges:()=>null}))
jest.mock('../../components/SafeAvatar',()=>({SafeAvatar:()=>null}))
jest.mock('../../core/runtimeBackend',()=>({...jest.requireActual('../../core/runtimeBackend'),rewriteRuntimeStorageUrl:(value:string)=>value}))
jest.mock('../../core/media/storageReadClient',()=>({trustedStorageBackend:(value:string)=>value.startsWith('https://private.test/')?'https://private.test':''}))
jest.mock('../../core/media/browserStreaming',()=>({browserStreamingSource:jest.fn(),cachedBrowserStreamingSource:()=>undefined,subscribeBrowserStreamingRecovery:()=>()=>{}}))
const stream=jest.mocked(browserStreamingSource)
const item={id:'video-qa',title:'Video QA',authorId:'author',videoUrl:'https://private.test/storage/v1/object/public/videos/qa.mp4',coverUrl:'',authorName:'Persona',description:'',authorAvatarUrl:'',authorAvatarMediaType:'',createdAt:'',likeCount:0,commentCount:0,favoriteCount:0,likedByMe:false,favoritedByMe:false,sharedByMe:false,viewCount:0,durationSeconds:10,trimStartSeconds:0,trimEndSeconds:10}
test('the visible retry button recovers a video whose protected source never registered',async()=>{
 jest.useFakeTimers()
 Object.defineProperty(HTMLMediaElement.prototype,'play',{configurable:true,value:jest.fn(async()=>{})})
 Object.defineProperty(HTMLMediaElement.prototype,'load',{configurable:true,value:jest.fn()})
 updateStorageReadIdentity('media-qa','fallback')
 stream.mockRejectedValue(new Error('MEDIA_STREAMING_UNAVAILABLE'))
 const {container,unmount}=render(<MemoryRouter><ReelFeedCard item={item} eager muted onMutedChange={()=>{}} onLike={()=>{}} onFavorite={()=>{}} onShare={()=>{}} onToggleProfileShare={()=>{}} onComments={()=>{}} onActive={()=>{}} onViewed={()=>{}}/></MemoryRouter>)
 try {
  await act(async()=>{await Promise.resolve()})
  for(const delay of [350,1200,3000]) await act(async()=>{jest.advanceTimersByTime(delay);await Promise.resolve()})
  expect(stream).toHaveBeenCalledTimes(4)
  expect(container.querySelector('video')).not.toHaveAttribute('src')
  stream.mockResolvedValue('/__novvin_media/recovered-video')
  fireEvent.click(screen.getByRole('button',{name:'Reintentar'}))
  await act(async()=>{await Promise.resolve()})
  expect(container.querySelector('video')?.getAttribute('src')).toContain('/__novvin_media/recovered-video')
 } finally {unmount();invalidateStorageReadIdentity();stream.mockReset();jest.useRealTimers()}
})
