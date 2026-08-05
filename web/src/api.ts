import { collection, doc, getDoc, onSnapshot, query, where } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import { auth, db, functions } from "./firebase";
import type { Device } from "./types";

export async function redeemCode(code: string, helperName: string) { return (await httpsCallable(functions, "redeemPairingInvite")({ code, helperName })).data as { parentDeviceId: string }; }
export async function requestSession(parentDeviceId: string, helperName: string) { return (await httpsCallable(functions, "requestSession")({ parentDeviceId, helperName })).data as { requestId: string }; }
export async function endSession(sessionId: string, reason = "helper_stopped") { await httpsCallable(functions, "endSession")({ sessionId, reason }); }
export async function getIceServers(sessionId: string) { return (await httpsCallable(functions, "getIceServers")({ sessionId })).data as { iceServers: RTCIceServer[] }; }
export function watchDevices(update: (devices: Device[]) => void) {
  return onSnapshot(query(collection(db, "trustedHelpers"), where("helperUserId", "==", auth.currentUser?.uid ?? ""), where("revokedAt", "==", null)), async snapshot => {
    const devices = await Promise.all(snapshot.docs.map(async trust => {
      const item = await getDoc(doc(db, "devices", trust.get("parentDeviceId")));
      return item.exists() ? ({ id: item.id, ...item.data() } as Device) : null;
    }));
    update(devices.filter((value): value is Device => value !== null));
  });
}
export function watchAcceptedSession(requestId: string, accepted: (sessionId: string) => void) {
  return onSnapshot(doc(db, "sessionRequests", requestId), snapshot => { const data = snapshot.data(); if (data?.state === "accepted" && data.sessionId) accepted(data.sessionId); });
}
