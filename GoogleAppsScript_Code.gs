/** Wolketoke no-PIN token broker.
 * IMPORTANT: anyone who can reach this public endpoint can request a short-lived token.
 * Keep the LiveKit API secret in Script Properties, never in HTML or the Sheet.
 * Private Sheet Config tab columns A:B: Key, Value. Required keys: LiveKitServerURL, DefaultRoomName.
 */
function doGet(e) {
  var p=(e&&e.parameter)||{}; var callback=String(p.callback||'');
  if(!/^wkcb_[A-Za-z0-9_]+$/.test(callback)) return ContentService.createTextOutput('Invalid callback');
  var result;
  try { if(p.action!=='connect') throw new Error('Unsupported action.'); result=createConnection_(String(p.device||'')); }
  catch(err){ result={ok:false,error:safeMessage_(err)}; }
  return ContentService.createTextOutput(callback+'('+JSON.stringify(result)+');').setMimeType(ContentService.MimeType.JAVASCRIPT);
}
function createConnection_(deviceId){
  if(!/^WK-[A-F0-9-]{4,40}$/i.test(deviceId)) throw new Error('Device ID is invalid. Please restart the app.');
  var props=PropertiesService.getScriptProperties();
  var spreadsheetId=props.getProperty('SPREADSHEET_ID'), apiKey=props.getProperty('LIVEKIT_API_KEY'), apiSecret=props.getProperty('LIVEKIT_API_SECRET');
  if(!spreadsheetId||!apiKey||!apiSecret) throw new Error('Administrator setup incomplete.');
  var ss=SpreadsheetApp.openById(spreadsheetId), cfg=ss.getSheetByName('Config');
  if(!cfg) throw new Error('Config sheet is missing.');
  var values=cfg.getDataRange().getValues(), config={};
  for(var i=1;i<values.length;i++) config[String(values[i][0]).trim()]=String(values[i][1]).trim();
  var serverUrl=config.LiveKitServerURL||'', room=config.DefaultRoomName||'wolketoke-main';
  if(!/^wss:\/\//i.test(serverUrl)) throw new Error('LiveKit server URL is not configured correctly.');
  if(!/^[A-Za-z0-9_-]{1,60}$/.test(room)) throw new Error('DefaultRoomName is invalid in Config.');
  // Create the room once with a hard limit of 10 participants. Use a new room
  // name if an older room already exists without this limit.
  ensureRoomLimit10_(serverUrl,room,apiKey,apiSecret);
  var identity=deviceId.replace(/[^A-Za-z0-9_.-]/g,'_').slice(0,50), displayName='Wolketoke '+identity;
  var now=Math.floor(Date.now()/1000), header={alg:'HS256',typ:'JWT'};
  var payload={iss:apiKey,sub:identity,name:displayName,nbf:now-5,iat:now,exp:now+3600,video:{roomJoin:true,room:room,canPublish:true,canSubscribe:true,canPublishData:true}};
  var unsigned=b64url_(JSON.stringify(header))+'.'+b64url_(JSON.stringify(payload));
  var signature=Utilities.computeHmacSha256Signature(unsigned,apiSecret,Utilities.Charset.UTF_8);
  var token=unsigned+'.'+Utilities.base64EncodeWebSafe(signature).replace(/=+$/,'');
  return {ok:true,serverUrl:serverUrl,token:token,expiresIn:3600,room:room,identity:identity};
}
function b64url_(s){return Utilities.base64EncodeWebSafe(s,Utilities.Charset.UTF_8).replace(/=+$/,'');}
function safeMessage_(err){return String(err&&err.message?err.message:'Request failed.').slice(0,180);}
function setupWolketokeSheet(){
  var id=PropertiesService.getScriptProperties().getProperty('1hVetEytFiiJO1ExMA_p8U_tbOyje9iI2oLDHTK7JzSQ');
  if(!id) throw new Error('First set SPREADSHEET_ID in Project Settings > Script Properties.');
  var ss=SpreadsheetApp.openById(id), cfg=ss.getSheetByName('Config')||ss.insertSheet('Config');
  if(cfg.getLastRow()===0) cfg.getRange(1,1,3,2).setValues([['Key','APIoDYQysLP4bXh'],['LiveKitServerURL','wss://yubaraj-chaulagain-woketoke-w28a6hzn.livekit.cloud
LIVEKIT_API_KEY=APIoDYQysLP4bXh
LIVEKIT_API_SECRET=0saoG5hsNxy1Xo3FpPwf8b8QuOObhKShPidZ4imB2mX'],['DefaultRoomName','wolketoke-main']]);
  else { var rows=cfg.getDataRange().getValues(); var keys=rows.map(function(r){return String(r[0]).trim()}); if(keys.indexOf('LiveKitServerURL')<0) cfg.appendRow(['LiveKitServerURL','wss://YOUR-LIVEKIT-HOST']); if(keys.indexOf('DefaultRoomName')<0) cfg.appendRow(['DefaultRoomName','wolketoke-main']); }
}

y

/** Create the configured LiveKit room with a 10-device limit if it does not exist. */
function ensureRoomLimit10_(serverUrl,roomName,apiKey,apiSecret){
  var host=String(serverUrl).replace(/^wss:\/\//i,'https://').replace(/^ws:\/\//i,'http://').replace(/\/$/,'');
  var now=Math.floor(Date.now()/1000);
  var header={alg:'HS256',typ:'JWT'};
  var payload={iss:apiKey,sub:'wolketoke-room-setup',nbf:now-5,iat:now,exp:now+300,video:{roomCreate:true}};
  var unsigned=b64url_(JSON.stringify(header))+'.'+b64url_(JSON.stringify(payload));
  var sig=Utilities.computeHmacSha256Signature(unsigned,apiSecret,Utilities.Charset.UTF_8);
  var jwt=unsigned+'.'+Utilities.base64EncodeWebSafe(sig).replace(/=+$/,'');
  var response=UrlFetchApp.fetch(host+'/twirp/livekit.RoomService/CreateRoom',{
    method:'post',contentType:'application/json',muteHttpExceptions:true,
    headers:{Authorization:'Bearer '+jwt},
    payload:JSON.stringify({name:roomName,maxParticipants:10})
  });
  var code=response.getResponseCode(),body=response.getContentText();
  if(code>=200&&code<300)return;
  // Room already exists: retain it. To apply the limit, change DefaultRoomName
  // to a fresh room name in the Config sheet, then reconnect.
  if(/already exists|already_exists|room exists/i.test(body))return;
  throw new Error('Could not create the 10-device room. LiveKit response '+code+': '+body.slice(0,160));
}
