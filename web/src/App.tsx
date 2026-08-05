import { useEffect, useState } from "react";
import { onAuthStateChanged, type User } from "firebase/auth";
import { auth, login, logout } from "./firebase";
import { redeemCode, requestSession, watchAcceptedSession, watchDevices } from "./api";
import type { Device } from "./types";
import { RemoteConsole } from "./RemoteConsole";

export default function App() {
  const [user, setUser] = useState<User | null>(auth.currentUser); const [devices, setDevices] = useState<Device[]>([]); const [code, setCode] = useState("");
  const [name, setName] = useState(""); const [message, setMessage] = useState(""); const [sessionId, setSessionId] = useState<string>();
  useEffect(() => onAuthStateChanged(auth, setUser), []); useEffect(() => user ? watchDevices(setDevices) : undefined, [user]);
  if (!user) return <main className="landing"><h1>KinPilot</h1><p>Private, consent-first help for your family’s Android devices.</p><button onClick={() => void login()}>Continue with Google</button></main>;
  if (sessionId) return <RemoteConsole sessionId={sessionId} onEnded={() => setSessionId(undefined)} />;
  const pair = async () => { try { await redeemCode(code, name || user.displayName || "Family helper"); setCode(""); setMessage("Paired. You can now request a session."); } catch { setMessage("The code is invalid, expired, or already used."); } };
  const request = async (device: Device) => { try { const value = await requestSession(device.id, name || user.displayName || "Family helper"); setMessage(`Waiting for ${device.name} to approve…`); watchAcceptedSession(value.requestId, setSessionId); } catch { setMessage("This device is unavailable or no longer paired."); } };
  return <main className="dashboard"><header><div><strong>KinPilot</strong><small>{user.email}</small></div><button className="quiet" onClick={() => void logout()}>Sign out</button></header><section><h1>Your family devices</h1><p>The parent must approve every request and Android’s screen-sharing prompt.</p><label>Your displayed name<input value={name} onChange={event => setName(event.target.value)} placeholder={user.displayName ?? "Family helper"}/></label><div className="devices">{devices.map(device => <article key={device.id}><div><strong>{device.name}</strong><small>Android parent device</small></div><button onClick={() => void request(device)}>Request help</button></article>)}</div></section><section className="pair"><h2>Pair another device</h2><p>Enter the one-time 8-digit code shown on the parent phone.</p><div><input inputMode="numeric" value={code} maxLength={9} onChange={event => setCode(event.target.value)}/><button disabled={!/^\d{4}[ -]?\d{4}$/.test(code)} onClick={() => void pair()}>Pair</button></div></section><p aria-live="polite">{message}</p></main>;
}
