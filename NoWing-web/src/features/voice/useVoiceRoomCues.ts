import {useEffect,useMemo,useState} from 'react'
import {VoiceRoomCuePlayer,VoiceRoomCuePolicy,readVoiceCueSettings,voiceCueStorageKey,type VoiceCue,type VoiceCueSettings,type CueParticipant} from './voiceRoomCues'

class RoomCues {
  private policy:VoiceRoomCuePolicy
  private player=new VoiceRoomCuePlayer()
  private timer:ReturnType<typeof setTimeout>|undefined
  private settings:VoiceCueSettings
  private deafened=false
  readonly sessionKey:string
  constructor(selfId:string,roomId:string){this.sessionKey=selfId+':'+roomId;this.policy=new VoiceRoomCuePolicy(selfId);this.settings=readVoiceCueSettings(selfId)}
  configure(settings:VoiceCueSettings,deafened:boolean){this.settings=settings;this.deafened=deafened}
  prepare(){return this.player.prepare()}
  output(id:string){this.player.setOutput(id)}
  private play(cues:VoiceCue[]){if(cues[0])this.player.play(cues[0],this.settings,this.deafened)}
  connect(muted:boolean){this.play(this.policy.connect(muted))}
  baseline(peers:CueParticipant[]){this.policy.baseline(peers)}
  join(peer:CueParticipant){this.play(this.policy.join(peer))}
  mute(id:string,muted:boolean){this.play(this.policy.mute(id,muted))}
  left(socketId:string){this.policy.leave(socketId,performance.now());clearTimeout(this.timer);this.timer=setTimeout(()=>this.play(this.policy.drain(performance.now())),725)}
  suspend(){clearTimeout(this.timer);this.policy.suspend()}
  leave(){this.play(this.policy.leaveSelf());const finishing=this.player;this.player=new VoiceRoomCuePlayer();setTimeout(()=>finishing.close(),350)}
  dispose(){this.suspend();this.player.close();this.player=new VoiceRoomCuePlayer()}
}

export function useVoiceRoomCues(accountId:string,roomId:string,deafened:boolean,outputId:string){
  const session=useMemo(()=>new RoomCues(accountId,roomId),[accountId,roomId])
  const [settings,setState]=useState<VoiceCueSettings>(()=>readVoiceCueSettings(accountId))
  useEffect(()=>{setState(readVoiceCueSettings(accountId))},[accountId])
  useEffect(()=>{session.configure(settings,deafened)},[session,settings,deafened])
  useEffect(()=>{session.output(outputId)},[session,outputId])
  useEffect(()=>()=>session.dispose(),[session])
  const setSettings=(next:VoiceCueSettings)=>{setState(next);try{localStorage.setItem(voiceCueStorageKey(accountId),JSON.stringify(next))}catch{/* Storage may be unavailable in private browsing. */}}
  return {session,settings,setSettings}
}
