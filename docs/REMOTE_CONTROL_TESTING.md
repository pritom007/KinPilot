# Remote-control verification

Install the APK from the successful PR workflow on both phones. The unified app identifies this build as version 0.3.0. No Android build is needed on the developer's Mac.

## Before starting

On the phone receiving help, open Get support and enable KinPilot in Android Accessibility settings. Return to KinPilot and confirm it says **Remote control is ready**. Enabling screen sharing alone does not grant input access. The app explains the Accessibility access before opening settings.

Create a code, enter it on the helper phone or browser, accept the named helper on the host, and approve Android's whole-display sharing prompt.

## Device acceptance checks

1. Wait for control readiness, then tap an ordinary button directly in the shared video. It should operate the matching button, not a position in a separate touch pad.
2. Swipe a list, hold an icon, use Back/Home/Recents, and type into a focused ordinary editable field. Gesture feedback must reflect Android's completion or cancellation. Password fields must reject text.
3. Rotate the host and the unified controller. Video should keep its proportions and touches should still line up. Black bars must not generate commands.
4. Press Back on the host while sharing. KinPilot should move to the background while its session notification remains active.
5. End from either side, lock the host, or turn off Accessibility. Control and capture must stop. A new session requires approval again.
6. Repeat with the browser controller and with phones on unrelated networks. Report the on-screen status and phone/Android version if an action fails; do not collect typed values or screenshots of private content.

## Automated coverage

GitHub Actions builds both Android modules and the instrumentation APK, runs pure coordinate tests, and runs actual Accessibility tap/typing/swipe/Home and replay/session rejection checks on an Android 35 emulator. The emulator test enables the service only in its disposable test environment. Production code cannot enable the service itself.

Web tests cover readiness requests on an already-open channel, retry cleanup, message parsing, and portrait/landscape coordinates. These tests do not prove two-device WebRTC performance or OEM-specific behavior.

## Releases

After merging a green PR, publish a new GitHub pre-release/tag from the merged commit. The workflow builds and tests that revision before attaching `KinPilot.apk` and its SHA-256 file. The in-app update link opens the releases list so pre-releases remain visible.

Builds still use Android debug signing. Updating an existing installation can require reinstalling if its earlier APK was signed with a different runner-generated key; consistent production signing is a separate setup step.
