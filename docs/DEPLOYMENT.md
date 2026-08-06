# Deployment and verification

## Render rendezvous service

Create a Render Blueprint from this repository. `render.yaml` creates the free `kinpilot-rendezvous` Web Service, builds only the `server` workspace, exposes `/health`, and accepts the production Netlify origin. No database, disk, secret, or payment method is required.

The free service sleeps after inactivity. The parent app shows a waking message while the first WebSocket connection starts it. Active WebSocket heartbeat traffic keeps it awake during a session. A restart intentionally invalidates every temporary code and session.

After Render assigns the hostname, use `https://<render-host>` for both Android's `RendezvousClient.DEFAULT_URL` and Netlify's `VITE_SIGNALING_URL`. The clients use short HTTPS polling for the small signaling messages; screen video and controls remain direct WebRTC traffic.

## Netlify browser console

The root `netlify.toml` builds the `web` workspace and publishes `web/dist`. Set one production environment variable:

```text
VITE_SIGNALING_URL=https://kinpilot-rendezvous.onrender.com
```

Trigger a production deploy after changing the value. The Content Security Policy permits WebSocket connections only to Render hosts.

## Android

Open `android/` with Android Studio using JDK 17 and Android SDK 35. Build `parent` and `helper` on Android 12 or later. No Firebase configuration is needed.

GitHub Actions builds both debug APKs in CI without using local disk space. Open a pull request that changes Android code to download a `KinPilot-apks-<sha>` workflow artifact containing `KinPilot-Parent-debug.apk`, `KinPilot-Helper-debug.apk`, and a `SHA256SUMS` checksum file. To publish downloadable APKs on the repository release page, create and publish a GitHub release from the target commit or tag; the `Build KinPilot APK` workflow attaches both APKs and the checksums to that release automatically.

Before installing on family devices:

1. Create and protect a private Android signing key outside the repository.
2. Build signed release APKs for both modules.
3. On the parent phone, enable KinPilot's disclosed Accessibility service and notifications.
4. Start support, scan the QR or enter its code, confirm the displayed helper name, and accept Android's screen-sharing prompt.

## Security and reliability checklist

- Verify invalid and expired codes fail, only one helper can join, and decline closes the pending helper.
- Verify every new session requires parent acceptance and a fresh MediaProjection system prompt.
- Verify the one-hour timer, Stop action, screen lock, capture revocation, service termination, and WebSocket loss end access.
- Verify password/secure fields reject typing and protected windows are not visible.
- Verify SDP/ICE messages contain no screen content and typed values, screen pixels, and control commands never enter application logs or the rendezvous server.
- Test unrelated Wi-Fi and cellular networks. Public STUN cannot connect every NAT combination; this free MVP must report those failures clearly.
- Do not add a static TURN credential to either app. Add short-lived relay credentials later if family networks prove incompatible.

## Current MVP boundary

Voice, clipboard/file transfer, recording, unattended access, stored relationships, multi-helper concurrency, and TURN relay are intentionally excluded. Sessions are temporary and cannot survive a Render restart or either peer disconnecting.
