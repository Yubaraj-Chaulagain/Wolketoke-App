# Wolketoke Walkie-Talkie

Offline walkie-talkie mode is retained. Online mode uses LiveKit Cloud for a 10-person full-duplex voice room.

Read `ONLINE_APPS_SCRIPT_SETUP_NP.txt` before building the APK. Add `SPREADSHEET_ID`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, and `LIVEKIT_SERVER_URL` as Apps Script Script Properties. Replace `APPS_SCRIPT_URL` in both online.html copies with your deployed Web App `/exec` URL. Never commit the LiveKit API Secret.


## Relay Mode (Beta)

This version includes Wi-Fi relay and Bluetooth Classic RFCOMM relay. Read `RELAY_MODE_GUIDE_NP.txt` for pairing, setup, and limitations. Both the Main Host and Relay phone must run this updated APK for Bluetooth relay.


## Multi-hop Relay (Beta)
This source includes a four-relay chain/tree setup guide in `RELAY_MODE_GUIDE_NP.txt`. Each node accepts up to 13 downstream peers; the intended topology target is four relay phones plus 50 user phones. This is a design/admission target only, not a verified 50-device capacity claim. Run staged device/load tests before relying on it.
