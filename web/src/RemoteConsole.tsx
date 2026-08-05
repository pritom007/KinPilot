import { useEffect, useMemo, useRef, useState } from "react";
import { ControlSender } from "./control";
import { endSession } from "./api";
import { HelperRtcSession } from "./rtc";

export function RemoteConsole({ sessionId, onEnded }: { sessionId: string; onEnded: () => void }) {
  const video = useRef<HTMLVideoElement>(null); const pointer = useRef<{ x: number; y: number; at: number }>();
  const [connection, setConnection] = useState("connecting"); const [text, setText] = useState(""); const [feedback, setFeedback] = useState("");
  const rtc = useMemo(() => new HelperRtcSession(sessionId), [sessionId]);
  const control = useMemo(() => new ControlSender(value => rtc.send(value)), [rtc]);
  useEffect(() => {
    rtc.onStream = stream => { if (video.current) video.current.srcObject = stream; }; rtc.onState = setConnection;
    rtc.onControlResult = value => setFeedback((value as { accepted?: boolean; reason?: string }).accepted ? "Action sent" : `Unavailable: ${(value as { reason?: string }).reason ?? "unknown"}`);
    void rtc.start().catch(() => setConnection("failed")); return () => rtc.close();
  }, [rtc]);
  const point = (event: React.PointerEvent<HTMLVideoElement>) => { const rect = event.currentTarget.getBoundingClientRect(); return { x: (event.clientX - rect.left) / rect.width, y: (event.clientY - rect.top) / rect.height }; };
  const stop = async () => { rtc.close(); await endSession(sessionId); onEnded(); };
  return <main className="console">
    <header><div><strong>Remote support</strong><span className={`status ${connection}`}>{connection}</span></div><button className="danger" onClick={() => void stop()}>End session</button></header>
    <section className="device-stage"><video ref={video} autoPlay playsInline onPointerDown={event => { const p = point(event); pointer.current = { ...p, at: Date.now() }; event.currentTarget.setPointerCapture(event.pointerId); }} onPointerUp={event => { const start = pointer.current; if (!start) return; const end = point(event); const elapsed = Date.now() - start.at; const distance = Math.hypot(end.x - start.x, end.y - start.y); if (distance > .03) control.swipe(start.x, start.y, end.x, end.y, elapsed); else if (elapsed > 550) control.longPress(end.x, end.y); else control.tap(end.x, end.y); pointer.current = undefined; }} /></section>
    <nav><button onClick={() => control.action("BACK")}>Back</button><button onClick={() => control.action("HOME")}>Home</button><button onClick={() => control.action("RECENTS")}>Recents</button></nav>
    <form onSubmit={event => { event.preventDefault(); try { control.setText(text); setText(""); } catch (error) { setFeedback((error as Error).message); } }}><label>Type into the focused editable field</label><div><input value={text} maxLength={2000} onChange={event => setText(event.target.value)} autoComplete="off"/><button>Send text</button></div></form>
    <p aria-live="polite">{feedback}</p><p className="privacy">Protected and password fields intentionally reject remote typing. Screen contents are not recorded.</p>
  </main>;
}

