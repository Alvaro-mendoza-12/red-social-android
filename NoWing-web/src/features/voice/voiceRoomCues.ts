export type VoiceCue = 'join' | 'leave' | 'mute' | 'unmute'
export type CueParticipant = {userId:string;socketId:string;isMuted?:boolean}
/** Signal events, not REST/polling changes, drive audible transitions. */
export class VoiceRoomCuePolicy {
  private connected=false
  private ready=false
  private greeted=false
  private selfMuted=true
  private peers=new Map<string,CueParticipant>()
  private leaving=new Map<string,number>()
  private readonly selfId:string
  constructor(selfId:string){this.selfId=selfId}
  connect(muted:boolean):VoiceCue[]{
    this.connected=true;this.selfMuted=muted
    if(this.greeted)return []
    this.greeted=true;return ['join']
  }
  baseline(peers:CueParticipant[]){
    this.peers=new Map(peers.filter(p=>p.userId&&p.userId!==this.selfId).map(p=>[p.userId,{...p}]))
    this.leaving.clear();this.ready=true
  }
  join(peer:CueParticipant):VoiceCue[]{
    if(!this.connected||!this.ready||!peer.userId||peer.userId===this.selfId)return []
    const known=this.peers.has(peer.userId),rejoining=this.leaving.delete(peer.userId)
    this.peers.set(peer.userId,{...peer})
    return known||rejoining?[]:['join']
  }
  leave(socketId:string,now:number){
    if(!this.connected||!this.ready)return
    for(const [id,p] of this.peers)if(p.socketId===socketId){this.peers.delete(id);this.leaving.set(id,now+700)}
  }
  drain(now:number):VoiceCue[]{
    if(!this.connected||!this.ready)return []
    const cues:VoiceCue[]=[]
    for(const [id,at] of this.leaving)if(at<=now){this.leaving.delete(id);cues.push('leave')}
    return cues
  }
  mute(userId:string,muted:boolean):VoiceCue[]{
    if(!this.connected)return []
    if(userId===this.selfId){if(muted===this.selfMuted)return [];this.selfMuted=muted}
    else{const peer=this.peers.get(userId);if(!this.ready||!peer||peer.isMuted===muted)return [];peer.isMuted=muted}
    return [muted?'mute':'unmute']
  }
  suspend(){this.connected=false;this.ready=false;this.peers.clear();this.leaving.clear()}
  leaveSelf():VoiceCue[]{const was=this.connected;this.suspend();this.greeted=false;return was?['leave']:[]}
}

export type VoiceCueSettings={presence:boolean;microphone:boolean;volume:number}
export const defaultVoiceCueSettings:VoiceCueSettings={presence:true,microphone:true,volume:.35}
export function voiceCueStorageKey(accountId:string){return 'novvin:voice-cues:'+encodeURIComponent(accountId)}
export function readVoiceCueSettings(accountId:string):VoiceCueSettings{
  try{const s=JSON.parse(localStorage.getItem(voiceCueStorageKey(accountId))||'null');return {presence:typeof s?.presence==='boolean'?s.presence:true,microphone:typeof s?.microphone==='boolean'?s.microphone:true,volume:typeof s?.volume==='number'&&Number.isFinite(s.volume)?Math.min(1,Math.max(0,s.volume)):.35}}
  catch{return {...defaultVoiceCueSettings}}
}

/** Local effects only: never connected to the outgoing WebRTC stream. */
export class VoiceRoomCuePlayer {
  private context:AudioContext|null=null
  private buffers=new Map<VoiceCue,AudioBuffer>()
  private source:AudioBufferSourceNode|null=null
  private loading:Promise<void>|null=null
  private lastAt=-Infinity
  private closed=false
  private outputId=''
  async prepare():Promise<boolean>{
    try{
      if(this.closed)return false
      this.context??=new AudioContext()
      await Promise.race([this.context.resume(),new Promise(resolve=>setTimeout(resolve,250))])
      if(this.context.state!=='running')return false
      if(!this.loading){const ctx=this.context;this.loading=Promise.all((['join','leave','mute','unmute'] as VoiceCue[]).map(async cue=>{const r=await fetch('/sounds/voice-'+cue+'.wav');if(!r.ok)return;const buffer=await ctx.decodeAudioData(await r.arrayBuffer());if(!this.closed)this.buffers.set(cue,buffer)})).then(()=>{})}
      await this.loading;return this.context.state==='running'
    }catch{this.loading=null;return false}
  }
  setOutput(id:string){this.outputId=id;const c=this.context as (AudioContext&{setSinkId?:(id:string)=>Promise<void>})|null;if(c?.setSinkId)void c.setSinkId(id).catch(()=>{})}
  play(cue:VoiceCue,settings:VoiceCueSettings,deafened:boolean){
    if(this.closed||deafened||settings.volume<=0||((cue==='join'||cue==='leave')?!settings.presence:!settings.microphone))return
    const ctx=this.context,buffer=this.buffers.get(cue),now=performance.now()
    if(!ctx||ctx.state!=='running'||!buffer||now-this.lastAt<300)return
    this.lastAt=now;this.source?.stop();const source=ctx.createBufferSource(),gain=ctx.createGain();source.buffer=buffer;gain.gain.value=settings.volume;source.connect(gain);gain.connect(ctx.destination);source.onended=()=>{source.disconnect();gain.disconnect()};source.start();this.source=source
    if(this.outputId)this.setOutput(this.outputId)
  }
  close(){this.closed=true;this.source?.stop();this.source=null;this.buffers.clear();if(this.context)void this.context.close().catch(()=>{});this.context=null}
}
