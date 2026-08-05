import { addDoc, collection, doc, getDoc, onSnapshot, query, serverTimestamp, where } from "firebase/firestore";
import { auth, db } from "./firebase";
import { getIceServers } from "./api";

export class HelperRtcSession {
  readonly peer: RTCPeerConnection;
  private channel?: RTCDataChannel;
  private unsub?: () => void;
  onStream?: (stream: MediaStream) => void;
  onControlResult?: (value: unknown) => void;
  onState?: (state: RTCPeerConnectionState) => void;

  constructor(private readonly sessionId: string) {
    this.peer = new RTCPeerConnection({ iceServers: [{ urls: "stun:stun.l.google.com:19302" }] });
    this.peer.ontrack = event => this.onStream?.(event.streams[0] ?? new MediaStream([event.track]));
    this.peer.onconnectionstatechange = () => this.onState?.(this.peer.connectionState);
    this.peer.ondatachannel = event => this.attachChannel(event.channel);
    this.peer.onicecandidate = event => { if (event.candidate) void this.publish("ice", JSON.stringify(event.candidate.toJSON())); };
  }
  async start() {
    const uid = auth.currentUser?.uid; if (!uid) throw new Error("Sign in first");
    const relay = await getIceServers(this.sessionId);
    this.peer.setConfiguration({ iceServers: [{ urls: "stun:stun.l.google.com:19302" }, ...relay.iceServers] });
    const session = await getDoc(doc(db, "sessions", this.sessionId)); const parentUid = session.get("parentOwnerId") as string;
    this.unsub = onSnapshot(query(collection(db, "sessions", this.sessionId, "signals"), where("recipientId", "==", uid)), snapshot => {
      for (const change of snapshot.docChanges()) { if (change.type !== "added") continue; const message = change.doc.data(); void this.receive(message.kind, message.payload, parentUid); }
    });
  }
  send(value: string) { if (this.channel?.readyState !== "open") throw new Error("Control channel is not connected"); this.channel.send(value); }
  private async receive(kind: string, payload: string, parentUid: string) {
    if (kind === "offer") { await this.peer.setRemoteDescription({ type: "offer", sdp: payload }); const answer = await this.peer.createAnswer(); await this.peer.setLocalDescription(answer); await this.publish("answer", answer.sdp ?? "", parentUid); }
    if (kind === "ice") { try { await this.peer.addIceCandidate(JSON.parse(payload)); } catch { const [sdpMid, index, candidate] = payload.split("|", 3); await this.peer.addIceCandidate({ sdpMid, sdpMLineIndex: Number(index), candidate }); } }
  }
  private attachChannel(channel: RTCDataChannel) { this.channel = channel; channel.onmessage = event => { try { this.onControlResult?.(JSON.parse(event.data)); } catch { /* Never log control payloads. */ } }; }
  private async publish(kind: "answer" | "ice", payload: string, knownRecipient?: string) {
    const senderId = auth.currentUser?.uid; if (!senderId) return;
    const session = knownRecipient ? undefined : await getDoc(doc(db, "sessions", this.sessionId));
    const recipientId = knownRecipient ?? session?.get("parentOwnerId");
    await addDoc(collection(db, "sessions", this.sessionId, "signals"), { senderId, recipientId, kind, payload, createdAt: serverTimestamp() });
  }
  close() { this.unsub?.(); this.channel?.close(); this.peer.close(); }
}
