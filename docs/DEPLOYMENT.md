# Deployment and verification

## Firebase

Create separate development and production Firebase projects. Enable Firestore, Authentication (Google and email/password), Functions, Hosting, and App Check. Register `app.kinpilot.parent`, `app.kinpilot.helper`, and the web application. Place each downloaded `google-services.json` in `android/parent/` and `android/helper/`; these project-specific files are intentionally not committed.

Set the server-side invite pepper with `firebase functions:secrets:set PAIRING_CODE_PEPPER`. Set `TURN_CREDENTIALS_URL` and `TURN_API_TOKEN` the same way. Deploy rules, indexes, functions, and hosting with `firebase deploy`. Configure a Firestore TTL policy on `sessions.deleteAt`; session documents retain metadata for 30 days, while no screen or control payload is stored.

For local development, register App Check debug tokens rather than disabling callable enforcement. Add authorized web domains and restrict the Firebase API key to the intended APIs and origins.

## TURN

The `getIceServers` callable authenticates the session participant and exchanges the server-only API token for 15-minute relay credentials. Configure `TURN_CREDENTIALS_URL` for a provider endpoint that accepts `{ "ttl": 900, "sessionId": "…" }` and returns `{ "iceServers": [{ "urls": ["turns:…"], "username": "…", "credential": "…" }] }`. Put provider-specific adaptation behind that endpoint. Never bundle the provider master secret in Android or web code.

## Android

Open `android/` with Android Studio using JDK 17 and install Android SDK 35. Add the Firebase configuration files, sync Gradle, and run each app on Android 12 or later. During parent setup:

1. Sign in and register the parent device.
2. Read the disclosure and enable the app's Accessibility service.
3. Allow notifications.
4. Create a one-time code and pair the helper.

For release APKs, use a private signing key stored outside the repository. Test Play Integrity/App Check using the exact release signing certificate.

## Security checklist

- Verify unpaired accounts cannot list devices, request sessions, or read signaling.
- Verify used and expired codes fail, and simultaneous redemption produces only one trusted relationship.
- Verify every new session requires parent acceptance and a fresh MediaProjection system prompt.
- Verify Stop, screen lock, capture revocation, and process termination end control immediately.
- Verify password/secure fields reject typing and protected windows are not visible.
- Verify no SDP, ICE payload, typed text, screen pixels, or control messages enter analytics or application logs.
- Configure Firebase budget alerts, Functions error alerts, App Check enforcement metrics, and TURN bandwidth alerts.

## Current MVP boundary

Voice, clipboard/file transfer, recording, unattended access, multi-helper concurrency, account recovery UI, and managed-device provisioning are intentionally excluded. Safari and TURN-only connectivity require device testing before family deployment.
