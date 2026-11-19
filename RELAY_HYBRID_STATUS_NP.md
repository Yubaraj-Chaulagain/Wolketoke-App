# Wolketoke Hybrid Relay – implementation status

## Added in this source package
- `relay/RelayFrame.java`: length-prefixed frame format with origin and packet IDs.
- `relay/RelayTransport.java`: transport abstraction.
- `relay/WifiRelayTransport.java`: TCP stream transport for a reachable Wi-Fi peer.
- `relay/BluetoothRelayTransport.java`: Bluetooth Classic RFCOMM stream transport for paired devices.
- `relay/HybridRelayManager.java`: Wi-Fi-first connection attempt, Bluetooth fallback, reconnect loop, and packet de-duplication.
- Updated Android manifest with Bluetooth permissions/features.
- Updated app version and GitHub Actions build/verification workflow.

## Important integration and test limits
This is a transport-layer foundation, not a claim that end-to-end multi-hop audio is already wired into the existing `MainActivity` audio capture/playback pipeline. The current app's existing audio path is still its original Wi-Fi socket path. A later integration step must connect `AudioRecord` chunks to `RelayFrame.audio(...)`, route received audio frames to `AudioTrack`, and run a separate downstream listener/forwarding loop on relay nodes.

Bluetooth Classic RFCOMM requires devices to be paired and Android 12+ runtime `BLUETOOTH_CONNECT` permission to be granted. This manager selects one upstream transport at a time (Wi-Fi preferred, Bluetooth fallback); it does not bond devices automatically, guarantee seamless zero-loss switching, or create simultaneous Wi-Fi+Bluetooth mesh links. Wi-Fi peer address discovery, pairing UI, multi-peer fan-out, and on-device testing remain required.

The GitHub Actions workflow builds and uploads a debug APK artifact when run on GitHub. It has not been executed in this offline editing environment; a successful build is not asserted here.
