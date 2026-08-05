# KinPilot

KinPilot is consent-first remote assistance for Android 12+. It uses a tiny, stateless rendezvous service only to introduce two devices; screen video and controls then travel directly over encrypted WebRTC.

- `android/parent`: explicit approval, Android screen sharing, and accessibility-based controls.
- `android/helper`: native Android remote console.
- `android/protocol`: shared control protocol and rendezvous client.
- `web`: browser helper console hosted on Netlify.
- `server`: in-memory HTTPS rendezvous service hosted on Render.

## Privacy and safety

There are no accounts or database. A random one-time code expires after 10 minutes, permits one helper, and exists only in server memory. An accepted session expires after one hour. Render restarts erase all rooms. Every session requires the parent to accept the named helper and approve Android's system screen-capture dialog.

Secure windows, password fields, biometrics, and blocked system surfaces are not bypassed. The server never receives screen pixels, control commands, or typed text. V1 uses public STUN only, so connections behind restrictive carrier or corporate NAT may fail rather than fall back to a paid relay.

## Local development

1. Run `npm install`, `npm test`, and `npm run build` from the repository root.
2. Start signaling with `npm run dev -w server`.
3. Copy `web/.env.example` to `web/.env.local`, set `VITE_SIGNALING_URL=ws://localhost:8787/ws`, and run `npm run dev -w web`.
4. Open `android/` in Android Studio with JDK 17 and Android SDK 35 to build the parent and helper APKs.

## APK artifacts

GitHub Actions builds the Android debug APKs on pull requests, manual workflow runs, and published GitHub releases. PR runs expose a `kinpilot-debug-apks` artifact. Publishing a pre-release attaches `KinPilot-Parent-debug.apk` and `KinPilot-Helper-debug.apk` to the release page automatically.

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for Render, Netlify, and Android release steps.
