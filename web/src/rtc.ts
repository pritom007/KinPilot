import{RendezvousClient,type SignalKind}from"./rendezvous";
export type ControlStatus={ready:boolean;reason?:string};
export type ParsedControlMessage={kind:"status";status:ControlStatus}|{kind:"result";value:unknown};
export function sendControlPayload(channel:Pick<RTCDataChannel,"readyState"|"send">|undefined,value:string):boolean{
  if(channel?.readyState!=="open")return false;
  try{channel.send(value);return true;}catch{return false;}
}
export function parseControlMessage(data:string):ParsedControlMessage|undefined{
  try{
    const value=JSON.parse(data) as unknown;
    if(!value||typeof value!=="object")return undefined;
    const message=value as{type?:unknown;ready?:unknown;reason?:unknown};
    if(message.type==="controlStatus"){
      if(typeof message.ready!=="boolean")return undefined;
      return{kind:"status",status:{ready:message.ready,...(typeof message.reason==="string"?{reason:message.reason}:{})}};
    }
    return{kind:"result",value};
  }catch{return undefined;}
}
export class HelperRtcSession{
  readonly peer:RTCPeerConnection;
  private channel?:RTCDataChannel;
  private pendingStream?:MediaStream;
  private pendingIce:RTCIceCandidateInit[]=[];
  private remoteReady=false;
  private statusPoll?:ReturnType<typeof setInterval>;
  private lastStatus?:ControlStatus;
  private _onStream?:(stream:MediaStream)=>void;
  onControlResult?:(value:unknown)=>void;
  onControlStatus?:(status:ControlStatus)=>void;
  onState?:(state:RTCPeerConnectionState)=>void;
  set onStream(fn:((stream:MediaStream)=>void)|undefined){
    this._onStream=fn;
    if(fn&&this.pendingStream){fn(this.pendingStream);this.pendingStream=undefined;}
  }
  get onStream(){return this._onStream;}
  constructor(private readonly rendezvous:RendezvousClient){
    this.peer=new RTCPeerConnection({iceServers:[{urls:"stun:stun.cloudflare.com:3478"},{urls:"stun:stun.l.google.com:19302"}]});
    this.peer.ontrack=e=>{
      const stream=e.streams[0]??new MediaStream([e.track]);
      if(this._onStream)this._onStream(stream);
      else this.pendingStream=stream;
    };
    this.peer.onconnectionstatechange=()=>{
      if(this.peer.connectionState!=="connected")this.lastStatus=undefined;
      this.onState?.(this.peer.connectionState);
    };
    this.peer.ondatachannel=e=>this.attachChannel(e.channel);
    this.peer.onicecandidate=e=>{if(e.candidate)this.rendezvous.sendSignal("ice",JSON.stringify(e.candidate.toJSON()));};
  }
  async receive(kind:SignalKind,payload:string){
    if(kind==="offer"){
      await this.peer.setRemoteDescription({type:"offer",sdp:payload});
      this.remoteReady=true;
      const answer=await this.peer.createAnswer();
      await this.peer.setLocalDescription(answer);
      this.rendezvous.sendSignal("answer",answer.sdp??"");
      // Flush any ICE candidates that arrived before the offer.
      const buffered=this.pendingIce.splice(0);
      for(const c of buffered){try{await this.peer.addIceCandidate(c);}catch{/* ignore */}}
    }else if(kind==="ice"){
      const candidate=JSON.parse(payload) as RTCIceCandidateInit;
      if(!this.remoteReady){this.pendingIce.push(candidate);return;}
      try{await this.peer.addIceCandidate(candidate);}catch{/* ignore */}
    }
  }
  send(value:string){
    const sent=sendControlPayload(this.channel,value);
    if(!sent)this.onControlStatus?.({ready:false,reason:"control_channel_unavailable"});
    return sent;
  }
  private attachChannel(channel:RTCDataChannel){
    this.channel=channel;
    const probe=()=>sendControlPayload(channel,JSON.stringify({type:"controlStatusRequest"}));
    channel.onopen=probe;
    probe();
    clearInterval(this.statusPoll);
    this.statusPoll=setInterval(probe,2000);
    channel.onclose=()=>this.onControlStatus?.({ready:false,reason:"control_channel_unavailable"});
    channel.onerror=()=>this.onControlStatus?.({ready:false,reason:"control_channel_unavailable"});
    channel.onmessage=e=>{
      const parsed=parseControlMessage(String(e.data));
      if(!parsed)return;
      if(parsed.kind==="status"){
        if(this.lastStatus?.ready!==parsed.status.ready||this.lastStatus?.reason!==parsed.status.reason){
          this.lastStatus=parsed.status;
          this.onControlStatus?.(parsed.status);
        }
      }
      else{
        const result=parsed.value as{accepted?:unknown;reason?:unknown};
        if(result.accepted===false&&result.reason==="accessibility_unavailable")this.onControlStatus?.({ready:false,reason:result.reason});
        this.onControlResult?.(parsed.value);
      }
    };
  }
  close(){clearInterval(this.statusPoll);this.channel?.close();this.peer.close();}
}
