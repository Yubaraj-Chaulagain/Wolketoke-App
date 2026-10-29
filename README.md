# Yubaraj Walkie-Talkie — Auto Find test build

## What this version does
- Starts searching automatically when the app opens.
- Shows detected Yubaraj app hosts by phone/device name and IP.
- Automatically connects to the first Wi-Fi/Hotspot host it detects.
- Lets the user choose a detected Wi-Fi host from the list.
- Scans nearby Bluetooth devices and lists their names as a separate list.
- Keeps push-to-talk voice transport over the same Wi-Fi/Hotspot network.

## Important limitations
- Bluetooth scanning in this test build is for discovery/list display only; voice is not yet transported over Bluetooth.
- Android normally requires the user to enable/connect to a hotspot and approve Bluetooth permissions. A regular APK cannot silently turn on every phone's hotspot or silently join every network.
- All phones that should exchange voice must be on the same Wi-Fi/hotspot and one phone must be hosting.
- This is a test build, not a verified production release. Test on two Android phones first.

## Build APK
Push the project to GitHub, then use Actions > Build Yubaraj Walkie-Talkie APK. Download the `Yubaraj-Walkie-Talkie-APK` artifact.
