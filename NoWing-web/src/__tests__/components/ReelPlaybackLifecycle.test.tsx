import {act,fireEvent,render,screen} from '@testing-library/react'
import {MemoryRouter} from 'react-router-dom'
import {ReelFeedCard} from '../../features/reels/ReelsPage'
jest.mock('../../components/ProfileStatusBadges',()=>({ProfileStatusBadges:()=>null}))
jest.mock('../../components/SafeAvatar',()=>({SafeAvatar:()=>null}))
jest.mock('../../components/StreamingMedia',()=>{
 const React=jest.requireActual('react')
 return {StreamingVideo:React.forwardRef((props:Record<string,unknown>,ref:unknown)=>{
  const {onSourceUnavailable,retryKey,...rest}=props; void onSourceUnavailable; void retryKey
  return React.createElement('video',{...rest,ref})
 }),StreamingImage:()=>null}
})
let hidden=false,observe:(entries:Partial<IntersectionObserverEntry>[])=>void
const play=jest.fn(async()=>{}),pause=jest.fn()
const item={id:'video-qa',title:'Video QA',authorId:'author',videoUrl:'/qa.mp4',coverUrl:'',authorName:'Persona',description:'',authorAvatarUrl:'',authorAvatarMediaType:'',createdAt:'',likeCount:0,commentCount:0,favoriteCount:0,likedByMe:false,favoritedByMe:false,sharedByMe:false,viewCount:0,durationSeconds:10,trimStartSeconds:0,trimEndSeconds:10}
function mount(){return render(<MemoryRouter><ReelFeedCard item={item} eager muted onMutedChange={()=>{}} onLike={()=>{}} onFavorite={()=>{}} onShare={()=>{}} onToggleProfileShare={()=>{}} onComments={()=>{}} onActive={()=>{}} onViewed={()=>{}}/></MemoryRouter>)}
beforeEach(()=>{
 jest.useFakeTimers();hidden=false;play.mockClear();pause.mockClear()
 Object.defineProperty(document,'hidden',{configurable:true,get:()=>hidden})
 Object.defineProperty(HTMLMediaElement.prototype,'play',{configurable:true,value:play})
 Object.defineProperty(HTMLMediaElement.prototype,'pause',{configurable:true,value:pause})
 window.IntersectionObserver=class {constructor(callback:typeof observe){observe=callback}observe(){}disconnect(){}unobserve(){}takeRecords(){return []}root=null;rootMargin='';thresholds=[]} as unknown as typeof IntersectionObserver
})
afterEach(()=>jest.useRealTimers())
test('automatic tab pause resumes the visible video while an explicit user pause is preserved',()=>{
 const {container}=mount(),video=container.querySelector('video')!
 act(()=>observe([{isIntersecting:true,intersectionRatio:1}]))
 expect(play).toHaveBeenCalledTimes(1)
 hidden=true;fireEvent(document,new Event('visibilitychange'));fireEvent.pause(video)
 hidden=false;fireEvent(document,new Event('visibilitychange'))
 expect(play).toHaveBeenCalledTimes(2)
 Object.defineProperty(video,'paused',{configurable:true,value:false});fireEvent.click(video);fireEvent.pause(video)
 hidden=true;fireEvent(document,new Event('visibilitychange'))
 hidden=false;fireEvent(document,new Event('visibilitychange'))
 expect(play).toHaveBeenCalledTimes(2)
})
test('very brief buffering does not flash a loading message and long stalls offer retry',()=>{
 mount();act(()=>observe([{isIntersecting:true,intersectionRatio:1}]))
 expect(screen.queryByText('Cargando video…')).toBeNull()
 act(()=>jest.advanceTimersByTime(450));expect(screen.getByText('Cargando video…')).toBeInTheDocument()
 act(()=>jest.advanceTimersByTime(14550));expect(screen.getByRole('button',{name:'Reintentar'})).toBeInTheDocument()
})
