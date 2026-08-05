import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { WebSocketServer, type RawData, type WebSocket } from "ws";
import { MAX_MESSAGE_BYTES, parseMessage, type ClientMessage } from "./protocol.js";
import { RoomRegistry, type Peer } from "./rooms.js";

const port = Number(process.env.PORT ?? 8080);
const allowedOrigin = process.env.ALLOWED_ORIGIN ?? "https://kinpilot.netlify.app";
const registry = new RoomRegistry();
const attempts = new Map<string, { count: number; resetAt: number }>();
const clients = new Map<string, HttpPeer>();

class HttpPeer implements Peer {
  readonly OPEN = 1;
  readyState = 1;
  readonly messages: string[] = [];
  lastSeen = Date.now();
  send(value: string) { if (this.readyState === this.OPEN && this.messages.length < 100) this.messages.push(value); }
  close(_code?: number, reason = "ended") { this.readyState = 3; this.messages.push(JSON.stringify({ type: "ended", reason })); }
}

const server = createServer(async (request, response) => {
  setCors(request, response);
  if (request.method === "OPTIONS") { response.writeHead(204); response.end(); return; }
  if (request.url === "/health") return json(response, 200, { status: "ok" });
  const url = new URL(request.url ?? "/", "http://localhost");
  if (url.pathname === "/api/connect" && request.method === "POST") {
    const id = randomUUID(), peer = new HttpPeer(); clients.set(id, peer); return json(response, 200, { clientId: id });
  }
  if (url.pathname === "/api/message" && request.method === "POST") {
    const body = await readJson(request), peer = body && typeof body.clientId === "string" ? clients.get(body.clientId) : undefined;
    const message = body ? parseMessage(JSON.stringify(body.message)) : null;
    if (!peer || !message) return json(response, 400, { error: "invalid_request" });
    peer.lastSeen = Date.now(); dispatch(peer, message); return json(response, 200, { ok: true });
  }
  if (url.pathname === "/api/poll" && request.method === "GET") {
    const peer = clients.get(url.searchParams.get("clientId") ?? "");
    if (!peer) return json(response, 404, { error: "client_not_found" });
    peer.lastSeen = Date.now(); const messages = peer.messages.splice(0); return json(response, 200, { messages: messages.map(value => JSON.parse(value)) });
  }
  json(response, 404, { error: "not_found" });
});

const websocket = new WebSocketServer({ noServer: true, maxPayload: MAX_MESSAGE_BYTES, perMessageDeflate: false });
server.on("upgrade", (request, socket, head) => {
  const origin = request.headers.origin, ip = clientIp(request);
  if (request.url !== "/ws" || !validOrigin(origin) || limited(ip)) { socket.write("HTTP/1.1 403 Forbidden\r\n\r\n"); socket.destroy(); return; }
  websocket.handleUpgrade(request, socket, head, client => websocket.emit("connection", client));
});
websocket.on("connection", (socket: WebSocket) => {
  socket.on("message", (data: RawData, binary: boolean) => {
    if (binary || Buffer.byteLength(data.toString()) > MAX_MESSAGE_BYTES) return socket.close(1009, "invalid_message");
    const message = parseMessage(data.toString()); if (!message) { socket.send('{"type":"error","code":"invalid_message"}'); return; }
    dispatch(socket, message);
  });
  socket.on("close", () => registry.removePeer(socket)); socket.on("error", () => registry.removePeer(socket));
});

function dispatch(peer: Peer, message: ClientMessage) {
  if (message.type === "create") registry.create(peer);
  else if (message.type === "join") registry.join(peer, message.code, message.helperName);
  else if (message.type === "respond") registry.respond(peer, message.requestId, message.accept);
  else if (message.type === "signal") registry.relay(peer, message);
  else if (message.type === "leave") registry.removePeer(peer, "peer_left");
  else peer.send('{"type":"pong"}');
}
function setCors(request: IncomingMessage, response: ServerResponse) { const origin=request.headers.origin; if(origin&&validOrigin(origin)){response.setHeader("access-control-allow-origin",origin);response.setHeader("vary","Origin");response.setHeader("access-control-allow-headers","content-type");response.setHeader("access-control-allow-methods","GET,POST,OPTIONS");} response.setHeader("cache-control","no-store"); }
function validOrigin(origin?: string) { return !origin || origin === allowedOrigin || origin.startsWith("http://localhost:"); }
function clientIp(request: IncomingMessage) { return request.headers["x-forwarded-for"]?.toString().split(",")[0]?.trim() ?? request.socket.remoteAddress ?? "unknown"; }
function json(response: ServerResponse, status: number, value: unknown) { response.writeHead(status, { "content-type": "application/json" }); response.end(JSON.stringify(value)); }
async function readJson(request: IncomingMessage): Promise<Record<string, unknown> | null> { const chunks: Buffer[]=[];let size=0;for await(const chunk of request){size+=chunk.length;if(size>MAX_MESSAGE_BYTES)return null;chunks.push(chunk);}try{return JSON.parse(Buffer.concat(chunks).toString()) as Record<string,unknown>;}catch{return null;} }
function limited(ip: string) { const now=Date.now(),value=attempts.get(ip);if(!value||value.resetAt<=now){attempts.set(ip,{count:1,resetAt:now+60_000});return false;}value.count++;return value.count>30; }
setInterval(()=>{const now=Date.now();for(const[id,peer]of clients)if(now-peer.lastSeen>2*60*1000){registry.removePeer(peer,"peer_disconnected");clients.delete(id);}for(const[ip,value]of attempts)if(value.resetAt<=now)attempts.delete(ip);},60_000).unref();
server.listen(port);
