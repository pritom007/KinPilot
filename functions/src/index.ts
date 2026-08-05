import { initializeApp } from "firebase-admin/app";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { randomUUID } from "node:crypto";
import { generatePairingCode, hashPairingCode, INVITE_TTL_MS, METADATA_TTL_MS, normalizeCode, requireString, SESSION_TTL_MS } from "./security";

initializeApp();
const db = getFirestore();
const pairingPepper = defineSecret("PAIRING_CODE_PEPPER");
const turnCredentialsUrl = defineSecret("TURN_CREDENTIALS_URL");
const turnApiToken = defineSecret("TURN_API_TOKEN");

function authenticated(request: { auth?: { uid: string; token: Record<string, unknown> } }): string {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in is required.");
  return request.auth.uid;
}
function appChecked(request: { app?: unknown }): void {
  if (!request.app) throw new HttpsError("failed-precondition", "App Check is required.");
}
function translated(error: unknown): never {
  if (error instanceof HttpsError) throw error;
  const message = error instanceof Error ? error.message : "invalid_request";
  throw new HttpsError("invalid-argument", message);
}

export const registerDevice = onCall({ enforceAppCheck: true }, async request => {
  const uid = authenticated(request); appChecked(request);
  try {
    const deviceId = requireString(request.data?.deviceId, "device_id");
    const name = requireString(request.data?.name, "name", 60);
    const role = request.data?.role;
    if (role !== "parent" && role !== "helper") throw new Error("invalid_role");
    await db.collection("devices").doc(deviceId).set({ ownerId: uid, name, role, platform: request.data?.platform === "web" ? "web" : "android", lastSeenAt: FieldValue.serverTimestamp() }, { merge: true });
    return { deviceId };
  } catch (error) { translated(error); }
});

export const createPairingInvite = onCall({ enforceAppCheck: true, secrets: [pairingPepper] }, async request => {
  const uid = authenticated(request); appChecked(request);
  try {
    const parentDeviceId = requireString(request.data?.parentDeviceId, "parent_device_id");
    const device = await db.collection("devices").doc(parentDeviceId).get();
    if (!device.exists || device.get("ownerId") !== uid || device.get("role") !== "parent") throw new HttpsError("permission-denied", "Only the parent device owner can invite helpers.");
    const code = generatePairingCode(); const id = randomUUID(); const now = Date.now();
    await db.collection("pairingInvites").doc(id).create({ parentDeviceId, ownerId: uid, codeHash: hashPairingCode(code, pairingPepper.value()), createdAt: Timestamp.fromMillis(now), expiresAt: Timestamp.fromMillis(now + INVITE_TTL_MS), redeemedAt: null });
    return { code, expiresAtEpochMs: now + INVITE_TTL_MS };
  } catch (error) { translated(error); }
});

export const redeemPairingInvite = onCall({ enforceAppCheck: true, secrets: [pairingPepper] }, async request => {
  const helperUserId = authenticated(request); appChecked(request);
  try {
    const code = normalizeCode(request.data?.code); const helperName = requireString(request.data?.helperName, "helper_name", 60);
    const codeHash = hashPairingCode(code, pairingPepper.value()); const query = await db.collection("pairingInvites").where("codeHash", "==", codeHash).limit(1).get();
    if (query.empty) throw new HttpsError("not-found", "Code is invalid or expired.");
    const inviteRef = query.docs[0]!.ref;
    const parentDeviceId = await db.runTransaction(async tx => {
      const invite = await tx.get(inviteRef); const expiresAt = invite.get("expiresAt") as Timestamp;
      if (invite.get("redeemedAt") || expiresAt.toMillis() <= Date.now()) throw new HttpsError("failed-precondition", "Code is invalid or expired.");
      const parentId = invite.get("parentDeviceId") as string;
      tx.update(inviteRef, { redeemedAt: FieldValue.serverTimestamp(), redeemedBy: helperUserId });
      tx.set(db.collection("trustedHelpers").doc(`${parentId}_${helperUserId}`), { parentDeviceId: parentId, helperUserId, helperName, createdAt: FieldValue.serverTimestamp(), revokedAt: null });
      return parentId;
    });
    return { parentDeviceId };
  } catch (error) { translated(error); }
});

export const requestSession = onCall({ enforceAppCheck: true }, async request => {
  const helperUserId = authenticated(request); appChecked(request);
  try {
    const parentDeviceId = requireString(request.data?.parentDeviceId, "parent_device_id"); const helperName = requireString(request.data?.helperName, "helper_name", 60);
    const trust = await db.collection("trustedHelpers").doc(`${parentDeviceId}_${helperUserId}`).get();
    if (!trust.exists || trust.get("revokedAt")) throw new HttpsError("permission-denied", "This helper is not paired.");
    const id = randomUUID(); const now = Date.now();
    await db.collection("sessionRequests").doc(id).create({ parentDeviceId, helperUserId, helperName, state: "requested", createdAt: Timestamp.fromMillis(now), expiresAt: Timestamp.fromMillis(now + SESSION_TTL_MS) });
    return { requestId: id, expiresAtEpochMs: now + SESSION_TTL_MS };
  } catch (error) { translated(error); }
});

export const respondToSession = onCall({ enforceAppCheck: true }, async request => {
  const parentOwnerId = authenticated(request); appChecked(request);
  try {
    const requestId = requireString(request.data?.requestId, "request_id"); const accept = request.data?.accept === true; const requestRef = db.collection("sessionRequests").doc(requestId);
    return await db.runTransaction(async tx => {
      const sessionRequest = await tx.get(requestRef); if (!sessionRequest.exists || sessionRequest.get("state") !== "requested") throw new HttpsError("failed-precondition", "Request is no longer active.");
      const device = await tx.get(db.collection("devices").doc(sessionRequest.get("parentDeviceId")));
      if (device.get("ownerId") !== parentOwnerId) throw new HttpsError("permission-denied", "Only the parent can respond.");
      if ((sessionRequest.get("expiresAt") as Timestamp).toMillis() <= Date.now()) throw new HttpsError("deadline-exceeded", "Request expired.");
      if (!accept) { tx.update(requestRef, { state: "declined", respondedAt: FieldValue.serverTimestamp() }); return { sessionId: null }; }
      const sessionId = randomUUID(); const now = Date.now();
      tx.update(requestRef, { state: "accepted", respondedAt: FieldValue.serverTimestamp(), sessionId });
      tx.create(db.collection("sessions").doc(sessionId), { parentDeviceId: sessionRequest.get("parentDeviceId"), parentOwnerId, helperUserId: sessionRequest.get("helperUserId"), participantIds: [parentOwnerId, sessionRequest.get("helperUserId")], state: "accepted", createdAt: Timestamp.fromMillis(now), expiresAt: Timestamp.fromMillis(now + SESSION_TTL_MS), deleteAt: Timestamp.fromMillis(now + METADATA_TTL_MS) });
      return { sessionId };
    });
  } catch (error) { translated(error); }
});

export const endSession = onCall({ enforceAppCheck: true }, async request => {
  const uid = authenticated(request); appChecked(request);
  try {
    const sessionId = requireString(request.data?.sessionId, "session_id"); const reason = requireString(request.data?.reason, "reason", 40); const ref = db.collection("sessions").doc(sessionId);
    await db.runTransaction(async tx => { const session = await tx.get(ref); if (!(session.get("participantIds") as string[] | undefined)?.includes(uid)) throw new HttpsError("permission-denied", "Not a session participant."); tx.update(ref, { state: "ended", endedReason: reason, endedAt: FieldValue.serverTimestamp() }); });
    return { ended: true };
  } catch (error) { translated(error); }
});

export const revokeHelper = onCall({ enforceAppCheck: true }, async request => {
  const uid = authenticated(request); appChecked(request);
  try {
    const parentDeviceId = requireString(request.data?.parentDeviceId, "parent_device_id"); const helperUserId = requireString(request.data?.helperUserId, "helper_user_id");
    const device = await db.collection("devices").doc(parentDeviceId).get(); if (device.get("ownerId") !== uid) throw new HttpsError("permission-denied", "Only the parent can revoke helpers.");
    await db.collection("trustedHelpers").doc(`${parentDeviceId}_${helperUserId}`).update({ revokedAt: FieldValue.serverTimestamp() }); return { revoked: true };
  } catch (error) { translated(error); }
});

export const getIceServers = onCall({ enforceAppCheck: true, secrets: [turnCredentialsUrl, turnApiToken] }, async request => {
  const uid = authenticated(request); appChecked(request);
  try {
    const sessionId = requireString(request.data?.sessionId, "session_id");
    const session = await db.collection("sessions").doc(sessionId).get();
    if (!session.exists || !(session.get("participantIds") as string[]).includes(uid) || session.get("state") === "ended") throw new HttpsError("permission-denied", "No active session access.");
    const response = await fetch(turnCredentialsUrl.value(), { method: "POST", headers: { authorization: `Bearer ${turnApiToken.value()}`, "content-type": "application/json" }, body: JSON.stringify({ ttl: 900, sessionId }) });
    if (!response.ok) throw new HttpsError("unavailable", "TURN provider unavailable.");
    const body = await response.json() as { iceServers?: Array<{ urls: string | string[]; username?: string; credential?: string }> };
    const iceServers = body.iceServers?.filter(server => {
      const urls = Array.isArray(server.urls) ? server.urls : [server.urls];
      return urls.length > 0 && urls.every(url => /^(stun|turn|turns):/.test(url)) && (!server.credential || server.credential.length <= 512);
    });
    if (!iceServers?.length) throw new HttpsError("data-loss", "TURN provider returned invalid credentials.");
    return { iceServers, expiresAtEpochMs: Date.now() + 14 * 60 * 1000 };
  } catch (error) { translated(error); }
});
