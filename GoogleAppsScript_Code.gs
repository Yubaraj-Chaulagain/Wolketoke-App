/**
 * WOLKETOKE ONLINE GROUP VOICE — Google Apps Script backend
 * Compatible with online.html JSONP request:
 *   GET /exec?action=connect&device=<deviceId>&callback=<jsonpCallback>
 *
 * Script Properties (Project Settings > Script properties):
 *   SPREADSHEET_ID       = Google Sheet ID
 *   LIVEKIT_API_KEY      = LiveKit API Key
 *   LIVEKIT_API_SECRET   = LiveKit API Secret
 *   LIVEKIT_SERVER_URL   = wss://your-project.livekit.cloud
 *
 * Run setupWolketokeSheet() once, then deploy as Web app:
 * Execute as: Me | Who has access: Anyone
 */

const WK = {
  CONFIG_SHEET: 'Config',
  LOG_SHEET: 'ConnectionLog',
  DEFAULT_ROOM: 'wolketoke-group-10',
  MAX_PARTICIPANTS: 10,
  TOKEN_TTL_SECONDS: 3600,
  ROOM_EMPTY_TIMEOUT: 300,
  ROOM_MAX_DURATION: 86400
};

function setupWolketokeSheet() {
  const props = PropertiesService.getScriptProperties();
  const spreadsheetId = props.getProperty('SPREADSHEET_ID');
  if (!spreadsheetId) {
    throw new Error('पहिले Script Properties मा SPREADSHEET_ID राख्नुहोस्।');
  }

  const ss = SpreadsheetApp.openById(spreadsheetId);
  let config = ss.getSheetByName(WK.CONFIG_SHEET);
  if (!config) config = ss.insertSheet(WK.CONFIG_SHEET);
  config.clear();
  config.getRange(1, 1, 4, 2).setValues([
    ['Setting', 'Value'],
    ['LiveKitServerURL', props.getProperty('LIVEKIT_SERVER_URL') || 'wss://YOUR-PROJECT.livekit.cloud'],
    ['DefaultRoomName', WK.DEFAULT_ROOM],
    ['MaxParticipants', WK.MAX_PARTICIPANTS]
  ]);
  config.setFrozenRows(1);
  config.autoResizeColumns(1, 2);

  let log = ss.getSheetByName(WK.LOG_SHEET);
  if (!log) log = ss.insertSheet(WK.LOG_SHEET);
  if (log.getLastRow() === 0) {
    log.appendRow(['Timestamp', 'Event', 'DeviceId', 'RoomName', 'Result']);
    log.setFrozenRows(1);
  }
  return 'Wolketoke setup complete.';
}

function doGet(e) {
  const p = (e && e.parameter) || {};
  const callback = String(p.callback || '');
  const action = String(p.action || '');

  if (action !== 'connect') {
    return output_({ok: true, service: 'Wolketoke LiveKit Token Service'});
  }

  let result;
  try {
    result = createConnection_(String(p.device || 'unknown-device'));
  } catch (err) {
    result = {ok: false, error: err && err.message ? err.message : String(err)};
  }

  if (callback && /^[A-Za-z_$][0-9A-Za-z_$]{0,80}$/.test(callback)) {
    return ContentService.createTextOutput(callback + '(' + JSON.stringify(result) + ');')
      .setMimeType(ContentService.MimeType.JAVASCRIPT);
  }
  return output_(result);
}

function doPost(e) {
  return output_({ok: false, error: 'Use GET JSONP connect endpoint.'});
}

function createConnection_(deviceId) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const props = PropertiesService.getScriptProperties();
    const apiKey = required_(props.getProperty('LIVEKIT_API_KEY'), 'LIVEKIT_API_KEY');
    const apiSecret = required_(props.getProperty('LIVEKIT_API_SECRET'), 'LIVEKIT_API_SECRET');
    const spreadsheetId = required_(props.getProperty('SPREADSHEET_ID'), 'SPREADSHEET_ID');

    const ss = SpreadsheetApp.openById(spreadsheetId);
    const config = ss.getSheetByName(WK.CONFIG_SHEET);
    if (!config) throw new Error('Config sheet छैन। setupWolketokeSheet() Run गर्नुहोस्।');

    const values = config.getRange(1, 1, Math.max(config.getLastRow(), 1), 2).getValues();
    const settings = {};
    values.slice(1).forEach(r => settings[String(r[0])] = r[1]);

    const serverUrl = String(settings.LiveKitServerURL || props.getProperty('LIVEKIT_SERVER_URL') || '').trim();
    if (!/^wss:\/\/[A-Za-z0-9.-]+(?::\d+)?$/.test(serverUrl)) {
      throw new Error('Config sheet मा सही LiveKitServerURL राख्नुहोस् (wss://...).');
    }
    const roomName = String(settings.DefaultRoomName || WK.DEFAULT_ROOM).trim();
    if (!/^[A-Za-z0-9_-]{2,80}$/.test(roomName)) throw new Error('DefaultRoomName गलत छ।');

    const maxParticipants = Math.min(10, Math.max(2, Number(settings.MaxParticipants) || 10));
    ensureRoom_(serverUrl, apiKey, apiSecret, roomName, maxParticipants);

    const identity = 'wk-' + safeIdentity_(deviceId) + '-' + Utilities.getUuid().slice(0, 8);
    const now = Math.floor(Date.now() / 1000);
    const token = makeJwt_(apiKey, apiSecret, {
      iss: apiKey,
      sub: identity,
      nbf: now - 5,
      iat: now,
      exp: now + WK.TOKEN_TTL_SECONDS,
      name: 'Wolketoke Device',
      video: {
        roomJoin: true,
        room: roomName,
        canPublish: true,
        canSubscribe: true,
        canPublishData: true
      }
    });

    logEvent_(ss, 'TOKEN_ISSUED', deviceId, roomName, 'OK');
    return {ok: true, token: token, serverUrl: serverUrl, roomName: roomName, maxParticipants: maxParticipants};
  } finally {
    lock.releaseLock();
  }
}

function ensureRoom_(serverUrl, apiKey, apiSecret, roomName, maxParticipants) {
  const base = serverUrl.replace(/^wss:/, 'https:').replace(/\/+$/, '');
  const url = base + '/twirp/livekit.RoomService/CreateRoom';
  const serviceToken = makeJwt_(apiKey, apiSecret, {
    iss: apiKey,
    sub: 'wolketoke-room-admin',
    nbf: Math.floor(Date.now() / 1000) - 5,
    exp: Math.floor(Date.now() / 1000) + 300,
    video: {roomCreate: true, roomList: true, roomAdmin: true}
  });

  const response = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    payload: JSON.stringify({
      name: roomName,
      maxParticipants: maxParticipants,
      emptyTimeout: WK.ROOM_EMPTY_TIMEOUT,
    }),
    headers: {Authorization: 'Bearer ' + serviceToken},
    muteHttpExceptions: true
  });

  const status = response.getResponseCode();
  if (status >= 200 && status < 300) return;

  const body = response.getContentText() || '';
  // Room already exists: LiveKit returns ALREADY_EXISTS. It remains usable.
  if (/ALREADY_EXISTS|already exists/i.test(body)) return;
  throw new Error('LiveKit room create failed (' + status + '): ' + body.slice(0, 350));
}

function makeJwt_(apiKey, apiSecret, claims) {
  const header = {alg: 'HS256', typ: 'JWT'};
  const h = base64Url_(Utilities.newBlob(JSON.stringify(header)).getBytes());
  const p = base64Url_(Utilities.newBlob(JSON.stringify(claims)).getBytes());
  const signingInput = h + '.' + p;
  const signature = Utilities.computeHmacSha256Signature(signingInput, apiSecret, Utilities.Charset.UTF_8);
  return signingInput + '.' + base64Url_(signature);
}

function base64Url_(bytes) {
  return Utilities.base64EncodeWebSafe(bytes).replace(/=+$/g, '');
}

function required_(value, key) {
  if (!value || String(value).trim() === '') throw new Error(key + ' Script Properties मा राखिएको छैन।');
  return String(value).trim();
}

function safeIdentity_(value) {
  return String(value || 'device').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32) || 'device';
}

function logEvent_(ss, event, deviceId, roomName, result) {
  const sheet = ss.getSheetByName(WK.LOG_SHEET);
  if (sheet) sheet.appendRow([new Date(), event, String(deviceId).slice(0, 100), roomName, result]);
}

function output_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
