# Optional session voice

KinPilot 0.5 adds opt-in, two-way voice to an active support session. Each participant must press **Join voice** before that device requests microphone permission or transmits audio. Mute, leave, and rejoin do not restart screen sharing or remote control.

Audio is an Opus WebRTC track on the existing DTLS-SRTP peer connection. Render handles only rendezvous messages; it never receives or stores media. The apps do not request camera access and do not record, transcribe, log, or analyze voice.

Android uses WebRTC hardware echo cancellation and noise suppression when the device supports them. The in-session route control cycles through speaker, earpiece, wired headset, and Bluetooth devices that Android reports as available. Browser helpers request `audio` with echo cancellation, noise suppression, and automatic gain control, and expose a **Tap to hear audio** fallback when autoplay is blocked.

## Device acceptance checks

- Test Android-to-Android and Chrome/Edge/Safari-to-Android speech in both directions.
- Exercise join, mute, unmute, leave, and rejoin while video and controls remain active.
- Deny and revoke microphone permission; only local voice should stop.
- Test speaker, earpiece, wired, and Bluetooth routes plus an incoming phone call/audio-focus interruption.
- Test nearby-device echo and normal-distance conversation.
- Test Wi-Fi, cellular, latency, packet loss, and the known STUN-only limitation. Networks requiring TURN still cannot connect.
- End the session and confirm the microphone indicator disappears immediately.
