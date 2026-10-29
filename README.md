# Yubaraj Walkie-Talkie — Android offline prototype

This project implements a first-pass Android-to-Android local voice prototype using TCP audio and UDP host discovery.

## How to try
1. Upload the project to a GitHub repository on branch `main`.
2. Open Actions → `Build Yubaraj Walkie-Talkie APK` → Run workflow.
3. Download the `Yubaraj-Walkie-Talkie-APK` artifact and extract the APK.
4. Install on two Android phones and allow microphone access.
5. Enable one phone's Wi-Fi hotspot. Connect the second phone to that hotspot.
6. On the host phone, tap `START HOST`. On the second phone, tap `FIND HOST`; if host is found, tap `FIND HOST` again to connect.
7. Hold `HOLD TO TALK` while speaking.

## Limitations
- Prototype only; it has not been built or tested on physical devices in this environment.
- Android hotspot broadcast/discovery differs across phone brands. If automatic discovery fails, the local network/discovery implementation needs device-specific troubleshooting.
- This prototype is Android-to-Android only. iPhone support needs a separate iOS app or a secure web client and is not included here.
- Audio is raw PCM over a local TCP connection, with no encryption, no echo cancellation and no packet jitter buffer. Use only on a trusted local network; do not treat as a secure communications product.
- Keep phones on the same Wi-Fi/hotspot and within range. 50–100 m is not guaranteed.
