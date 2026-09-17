import{useEffect,useMemo,useRef,useState}from"react";
import{ControlSender,videoPoint}from"./control";
import{RendezvousClient}from"./rendezvous";
import{HelperRtcSession}from"./rtc";

export function RemoteConsole({rendezvous,expiresAt,onEnded}:{rendezvous:RendezvousClient;expiresAt:number;onEnded:()=>void}){
  const video=useRef<HTMLVideoElement>(null);
  const audio=useRef<HTMLAudioElement>(null);
  const pointer=useRef<{x:number;y:number;at:number}>();
  const pendingStream=useRef<MediaStream>();
  const[connection,setConnection]=useState("connecting");
  const[controlReady,setControlReady]=useState(false);
  const[text,setText]=useState("");
  const[feedback,setFeedback]=useState("Checking remote control availability…");
  const[voiceJoined,setVoiceJoined]=useState(false);
  const[voiceMuted,setVoiceMuted]=useState(true);
  const[remoteVoice,setRemoteVoice]=useState("Other person has not joined voice");
  const[tapToHear,setTapToHear]=useState(false);
  const[voiceFeedback,setVoiceFeedback]=useState("Voice is optional and uses no camera.");
  const rtc=useMemo(()=>new HelperRtcSession(rendezvous),[rendezvous]);
  const control=useMemo(()=>new ControlSender(value=>{
    if(rtc.send(value))return;
    setControlReady(false);
    setFeedback("The control connection closed. Wait for the device to reconnect.");
  }),[rtc]);

  useEffect(()=>{
    const attachStream=(stream:MediaStream)=>{
      const el=video.current;
      if(!el){pendingStream.current=stream;return;}
      el.srcObject=stream;
      // Muted playback avoids strict browser autoplay policies blocking the shared screen.
      el.muted=true;
      const attempt=()=>el.play().catch(()=>{/* user gesture may be needed; retry on click */});
      attempt();
    };
    rtc.onStream=attachStream;
    rtc.onAudioStream=stream=>{
      const el=audio.current;if(!el)return;el.srcObject=stream;
      void el.play().then(()=>setTapToHear(false)).catch(()=>setTapToHear(true));
    };
    rtc.onVoiceState=state=>setRemoteVoice(!state.joined?"Other person has not joined voice":state.muted?"Other person is muted":"Other person joined voice");
    if(pendingStream.current){attachStream(pendingStream.current);pendingStream.current=undefined;}

    rtc.onState=state=>{
      setConnection(state);
      if(state==="connected"){
        // Re-try play once the media flow is actually up.
        const el=video.current;
        if(el&&el.paused)el.play().catch(()=>undefined);
      }else setControlReady(false);
    };
    rtc.onControlStatus=status=>{
      setControlReady(status.ready);
      setFeedback(status.ready?"Remote control is ready.":status.reason==="accessibility_unavailable"?"Ask the device owner to enable KinPilot in Android Accessibility settings.":"Waiting for the control connection…");
    };
    rtc.onControlResult=value=>{
      const result=value as{accepted?:boolean;reason?:string};
      setFeedback(result.accepted?"Action completed":result.reason==="accessibility_unavailable"
        ?"Remote control is unavailable. Ask the device owner to enable KinPilot in Android Accessibility settings."
        :`Unavailable: ${result.reason??"unknown"}`);
    };

    const previous=rendezvous.onMessage;
    rendezvous.onMessage=message=>{
      if(message.type==="signal")void rtc.receive(message.kind,message.payload).catch(()=>setConnection("failed"));
      else if(message.type==="ended")onEnded();
      else previous?.(message);
    };
    const expiry=window.setTimeout(onEnded,Math.max(0,expiresAt-Date.now()));
    return()=>{window.clearTimeout(expiry);rendezvous.onMessage=previous;rtc.close();};
  },[rtc,rendezvous,expiresAt,onEnded]);

  const point=(event:React.PointerEvent<HTMLVideoElement>)=>{
    const rect=event.currentTarget.getBoundingClientRect();
    return videoPoint(event.clientX-rect.left,event.clientY-rect.top,rect.width,rect.height,event.currentTarget.videoWidth,event.currentTarget.videoHeight);
  };
  const stop=()=>{rtc.close();rendezvous.close();onEnded();};
  const tapToPlay=()=>{const el=video.current;if(el&&el.paused)el.play().catch(()=>undefined);};

  return(
    <main className="console">
      <header>
        <div><strong>KinPilot support</strong><span className={`status ${connection}`}>{connection}</span></div>
        <button className="danger" onClick={stop}>End session</button>
      </header>
      <p className={`control-status ${controlReady?"ready":"unavailable"}`} role="status">
        {controlReady?"Remote control ready":feedback}
      </p>
      <audio ref={audio} autoPlay playsInline/>
      <section className="voice-panel" aria-label="Voice controls">
        <div><strong>Voice</strong><span>{voiceFeedback} {remoteVoice}.</span></div>
        <div className="voice-actions">
          {!voiceJoined?<button onClick={async()=>{try{await rtc.joinVoice();setVoiceJoined(true);setVoiceMuted(false);setVoiceFeedback("Voice connected.");await audio.current?.play().catch(()=>setTapToHear(true));}catch{setVoiceFeedback("Microphone permission was denied or unavailable.");}}}>Join voice</button>:<>
            <button onClick={()=>{const next=!voiceMuted;rtc.setVoiceMuted(next);setVoiceMuted(next);}}>{voiceMuted?"Unmute":"Mute"}</button>
            <button onClick={()=>{void rtc.leaveVoice();setVoiceJoined(false);setVoiceMuted(true);setVoiceFeedback("You left voice.");}}>Leave voice</button>
          </>}
          {tapToHear&&<button className="primary" onClick={()=>void audio.current?.play().then(()=>setTapToHear(false))}>Tap to hear audio</button>}
        </div>
      </section>
      <section className={`device-stage ${controlReady?"":"view-only"}`} onClick={tapToPlay}>
        <video
          ref={video}
          autoPlay
          playsInline
          muted
          onPointerDown={event=>{
            if(!controlReady)return;
            const p=point(event);
            if(!p)return;
            pointer.current={...p,at:Date.now()};
            event.currentTarget.setPointerCapture(event.pointerId);
          }}
          onPointerUp={event=>{
            if(!controlReady){pointer.current=undefined;return;}
            const start=pointer.current;
            if(!start)return;
            const end=point(event);
            pointer.current=undefined;
            if(!end)return;
            const elapsed=Date.now()-start.at,distance=Math.hypot(end.x-start.x,end.y-start.y);
            if(distance>.03)control.swipe(start.x,start.y,end.x,end.y,elapsed);
            else if(elapsed>550)control.longPress(end.x,end.y);
            else control.tap(end.x,end.y);
            pointer.current=undefined;
          }}
          onPointerCancel={()=>{pointer.current=undefined;}}
        />
        {connection!=="connected"&&<p className="stage-hint">Waiting for the parent’s screen to appear…</p>}
      </section>
      <nav>
        <button disabled={!controlReady} onClick={()=>control.action("BACK")}>Back</button>
        <button disabled={!controlReady} onClick={()=>control.action("HOME")}>Home</button>
        <button disabled={!controlReady} onClick={()=>control.action("RECENTS")}>Recents</button>
      </nav>
      <form onSubmit={event=>{event.preventDefault();if(!controlReady)return;try{control.setText(text);setText("");}catch(error){setFeedback((error as Error).message);}}}>
        <label>Type into the focused editable field</label>
        <div>
          <input disabled={!controlReady} value={text} maxLength={2000} onChange={event=>setText(event.target.value)} autoComplete="off"/>
          <button disabled={!controlReady||!text.trim()}>Send text</button>
        </div>
      </form>
      <p aria-live="polite">{feedback}</p>
      <p className="privacy">Protected and password fields reject remote typing. Nothing is recorded.</p>
    </main>
  );
}
