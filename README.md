# KinPilot

KinPilot is consent-first remote assistance for Android 12+. The repository contains:

- `android/parent`: screen sharing, explicit session approval, and accessibility-based controls.
- `android/helper`: trusted-device pairing and an Android remote console.
- `android/protocol`: shared, validated wire messages.
- `web`: browser helper console.
- `functions`: Firebase callable APIs for pairing and session authorization.
- `firestore.rules`: deny-by-default Firestore authorization.

## Safety model

Pairing never starts control. A parent must accept every request and approve Android's system screen-capture dialog. The foreground notification and in-app stop action terminate access immediately. Secure windows, password fields, biometrics, and blocked system surfaces are not bypassed. Video and control payloads travel through WebRTC and are never stored by the backend.

## Local setup

1. Create a Firebase project and enable Google/email authentication, Firestore, Functions, and App Check.
2. Copy `web/.env.example` to `web/.env.local` and provide the public Firebase web configuration.
3. Add each Android app's `google-services.json` under its module directory.
4. Configure TURN credentials through the short-lived credential endpoint described in `docs/DEPLOYMENT.md`; never ship a static TURN secret.
5. Run `npm install`, `npm test`, and `npm run build` from the repository root.
6. Open `android/` in Android Studio (JDK 17) and run the desired app on Android 12+.

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for deployment and verification steps.
