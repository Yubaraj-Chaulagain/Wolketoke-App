# Wolketoke Relay Status (Beta)

- Wi-Fi TCP relay and Bluetooth Classic RFCOMM relay paths are wired in `MainActivity`.
- Relay node accepts downstream Wi-Fi and Bluetooth peers while maintaining one upstream link.
- Audio and school-bell control frames are forwarded upstream/downstream using the existing stream protocol.
- Per-node downstream admission limit: 13 peers. This is intended to help distribute a 50-user target across four relay phones, with four relay phones additional to the 50 user devices.
- There is no central network-wide membership counter, adaptive bandwidth control, or proof of 50-device simultaneous audio capacity in this beta.
- Bluetooth Classic requires adjacent devices to be paired; Android 12+ Bluetooth permissions are required.
- Wi-Fi client + hotspot concurrency varies by handset. Use Bluetooth upstream plus Wi-Fi hotspot downstream when simultaneous Wi-Fi roles are unsupported.
- Use a tree/chain topology only; avoid redundant paths.
- Android build and real multi-phone load tests have not been run in this environment. Treat 50 devices as a target pending staged load testing.
