# Family app updates

The home screen checks published GitHub releases (including prereleases) when opened and every 15 minutes while displayed. WorkManager also checks every six hours with network connectivity, subject to Android battery scheduling. Allow notifications using the home screen's Update notifications action to receive one notification per new version. A newer `update.json` versionCode shows a direct APK download. Publish a release to offer the update to every installed 0.4.0+ app; installation still requires the device owner's Android approval. This is periodic update discovery, not instant push or silent installation.

## One-time signing setup

Configure GitHub Actions repository secrets: `KINPILOT_KEYSTORE_BASE64` (base64-encoded persistent Java keystore), `KINPILOT_STORE_PASSWORD`, `KINPILOT_KEY_ALIAS`, and `KINPILOT_KEY_PASSWORD`. Keep an encrypted backup of the keystore and passwords outside GitHub. Never commit the key. Release jobs deliberately fail if this signing setup is missing, rather than distribute APKs with changing debug identities.

Previous releases used disposable CI debug keys. Moving to the persistent key may require a one-time uninstall/reinstall and re-enabling Accessibility. Subsequent releases must reuse that key and increase `versionCode` in `android/parent/build.gradle.kts`. Never replace a published version with different app contents.

PR builds remain debug-signed test artifacts. After merging, publish a new release from main; CI tests, builds the signed APK, and attaches `KinPilot.apk`, `KinPilot.apk.sha256`, and `update.json`. Existing 0.3.0 apps need the first update installed manually; they cannot acquire this new updater remotely before installing it.

## Connection UX verification

- Get support immediately creates a code. Copy and Share send only the one-time code/link.
- Help someone supports QR scanning (camera permission requested on use), manual code editing, or deliberate Paste. Unrelated QR URLs are rejected. Cancelling the camera returns to the form.
- Name is stored locally; codes are not retained between app launches. The request button and keyboard Go send the request, and Cancel permits retry. The host still explicitly approves each session.
- Verify large font sizes, system dark mode, keyboard visibility, camera denial, middle-of-code edits, connection cancellation and re-entry on physical devices.
