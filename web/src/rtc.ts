import{RendezvousClient,type SignalKind}from"./rendezvous";
export class HelperRtcSession{
  readonly peer:RTCPeerConnection;
  private channel?:RTCDataChannel;
  private pendingStream?:MediaStream;
  private pendingIce:RTCIceCandidateInit[]=[];
  private remoteReady=false;
  private _onStream?:(stream:MediaStream)=>void;
  onControlResult?:(value:unknown)=>void;
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
    this.peer.onconnectionstatechange=()=>this.onState?.(this.peer.connectionState);
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
  send(value:string){if(this.channel?.readyState!=="open")throw new Error("Control channel is not connected");this.channel.send(value);}
  private attachChannel(channel:RTCDataChannel){this.channel=channel;channel.onmessage=e=>{try{this.onControlResult?.(JSON.parse(e.data));}catch{/* No control logs. */}};}
  close(){this.channel?.close();this.peer.close();}
}
