package com.yubaraj.walkietalkie;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.*;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.AudioManager;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class MainActivity extends Activity {
    static final int DISCOVERY_PORT=45454, AUDIO_PORT=45455, SAMPLE_RATE=16000, CHUNK=640;
    static final int REQ_PERMS=77;
    TextView status, role, wifiLabel, btLabel;
    Button hostBtn, rescanBtn, talkBtn, stopBtn, nameBtn;
    EditText nameInput;
    LinearLayout wifiList, btList;
    volatile boolean hosting=false, connected=false, talking=false, running=true, connecting=false;
    ServerSocket serverSocket;
    final List<Client> clients=new CopyOnWriteArrayList<>();
    Socket clientSocket;
    DataOutputStream clientOut;
    AudioTrack player;
    WifiManager.MulticastLock multicastLock;
    final Object writeLock=new Object();
    String hostAddress="";
    String deviceName="Walkie Phone";
    final Map<String,Peer> peers=Collections.synchronizedMap(new LinkedHashMap<>());
    final Map<String,String> bluetoothPeers=Collections.synchronizedMap(new LinkedHashMap<>());
    BluetoothAdapter btAdapter;
    BroadcastReceiver btReceiver;

    static class Peer {
        String ip, name, mode;
        long seen;
        Peer(String i,String n,String m){ip=i;name=n;mode=m;seen=System.currentTimeMillis();}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(16,24,39));
        getWindow().setNavigationBarColor(Color.rgb(16,24,39));
        deviceName=getPreferences(0).getString("deviceName", android.os.Build.MODEL==null?"Walkie Phone":android.os.Build.MODEL);
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(wm!=null){try{multicastLock=wm.createMulticastLock("yubaraj-walkie");multicastLock.setReferenceCounted(false);multicastLock.acquire();}catch(Exception ignored){}}
        BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        if(bm!=null) btAdapter=bm.getAdapter();
        buildUI();
        requestNeededPermissions();
        startDiscoveryListener();
        startAutoSearch();
        startBluetoothScan();
    }

    void requestNeededPermissions(){
        ArrayList<String> p=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=31){
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_SCAN);
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),REQ_PERMS);
    }

    void buildUI(){
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(22,20,22,24);root.setGravity(Gravity.CENTER_HORIZONTAL);root.setBackgroundColor(Color.rgb(16,24,39));
        TextView title=new TextView(this);title.setText("Yubaraj Walkie-Talkie");title.setTextColor(Color.WHITE);title.setTextSize(24);title.setGravity(Gravity.CENTER);title.setPadding(0,8,0,4);root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView sub=new TextView(this);sub.setText("Auto Find • Wi-Fi / Hotspot + Bluetooth scan");sub.setTextColor(Color.LTGRAY);sub.setGravity(Gravity.CENTER);sub.setPadding(0,0,0,16);root.addView(sub);
        nameInput=new EditText(this);nameInput.setSingleLine(true);nameInput.setText(deviceName);nameInput.setHint("Your display name");nameInput.setTextColor(Color.WHITE);nameInput.setHintTextColor(Color.LTGRAY);nameInput.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(117,209,255)));root.addView(nameInput,params());
        nameBtn=button("SAVE MY NAME");root.addView(nameBtn,params());
        role=new TextView(this);role.setText("Searching automatically…");role.setTextColor(Color.rgb(117,209,255));role.setTextSize(16);role.setGravity(Gravity.CENTER);root.addView(role,params());
        hostBtn=button("START HOST");rescanBtn=button("SEARCH AGAIN");talkBtn=button("HOLD TO TALK");stopBtn=button("DISCONNECT");
        root.addView(hostBtn,params());root.addView(rescanBtn,params());
        wifiLabel=section("Wi-Fi / Hotspot devices");root.addView(wifiLabel);
        wifiList=new LinearLayout(this);wifiList.setOrientation(1);root.addView(wifiList,new LinearLayout.LayoutParams(-1,-2));
        btLabel=section("Nearby Bluetooth devices (discovery only)");root.addView(btLabel);
        btList=new LinearLayout(this);btList.setOrientation(1);root.addView(btList,new LinearLayout.LayoutParams(-1,-2));
        talkBtn.setEnabled(false);talkBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(43,62,88)));
        talkBtn.setOnTouchListener((v,e)->{
            if(!connected&&!hosting){setStatus("पहिले एउटै Wi-Fi/Hotspot मा जोडिनुहोस् र Host खोज्नुहोस्।");return true;}
            if(e.getAction()==MotionEvent.ACTION_DOWN){startTalking();return true;}
            if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){stopTalking();return true;}
            return true;
        });
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(240,240);tp.gravity=Gravity.CENTER;tp.setMargins(0,12,0,12);root.addView(talkBtn,tp);
        root.addView(stopBtn,params());
        status=new TextView(this);status.setText("Auto search started. For voice, phones must share the same Wi-Fi/Hotspot.");status.setTextColor(Color.WHITE);status.setTextSize(14);status.setGravity(Gravity.CENTER);status.setPadding(0,14,0,0);root.addView(status,new LinearLayout.LayoutParams(-1,-2));
        scroll.addView(root);setContentView(scroll);
        hostBtn.setOnClickListener(v->startHost());
        rescanBtn.setOnClickListener(v->{renderLists();startBluetoothScan();setStatus("Searching again…");});
        stopBtn.setOnClickListener(v->disconnectAll());
        nameBtn.setOnClickListener(v->{String n=nameInput.getText().toString().trim();if(n.isEmpty()){setStatus("Please enter a name.");return;}deviceName=n;getPreferences(0).edit().putString("deviceName",n).apply();setStatus("Name saved: "+deviceName);if(hosting)setStatus("Name saved. Restart HOST so others see the updated name.");});
    }
    TextView section(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(17);t.setPadding(0,16,0,6);return t;}
    Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(43,62,88)));return b;}
    LinearLayout.LayoutParams params(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,5,0,5);return p;}
    void setStatus(String s){runOnUiThread(()->{if(status!=null)status.setText(s);});}

    void startAutoSearch(){
        setStatus("Searching for other Yubaraj Walkie-Talkie phones automatically…");
        new Thread(()->{while(running){renderLists();try{Thread.sleep(2500);}catch(Exception ignored){}}},"ui-refresh").start();
        // Auto-connect as soon as a Wi-Fi host beacon appears.
        new Thread(()->{
            long began=System.currentTimeMillis();
            while(running){
                if(!hosting&&!connected&&!connecting&&!hostAddress.isEmpty()){
                    String ip=hostAddress;hostAddress="";connectTo(ip);
                }
                // If no host is found after a short search, this phone becomes the first host.
                if(!hosting&&!connected&&!connecting&&System.currentTimeMillis()-began>8000&&peers.isEmpty()){
                    startHost(); began=System.currentTimeMillis();
                }
                try{Thread.sleep(800);}catch(Exception ignored){}
            }
        },"auto-connect-host-election").start();
    }
    void startHost(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestNeededPermissions();setStatus("Allow microphone permission, then tap START HOST.");return;}
        if(hosting)return;
        hosting=true;connected=true;
        runOnUiThread(()->{role.setText("HOST MODE • "+deviceName);talkBtn.setEnabled(true);hostBtn.setEnabled(false);});
        new Thread(()->{
            try{
                serverSocket=new ServerSocket();serverSocket.setReuseAddress(true);serverSocket.bind(new InetSocketAddress(AUDIO_PORT));
                setStatus("Host running as "+deviceName+". Other phones on the same hotspot will auto-connect.");
                while(hosting){Socket s=serverSocket.accept();s.setTcpNoDelay(true);Client c=new Client(s);clients.add(c);setStatus("Connected devices: "+clients.size());new Thread(()->readClient(c)).start();}
            }catch(Exception e){if(hosting)setStatus("Host error: "+e.getMessage());}
        },"host-server").start();
        new Thread(this::sendBeacon,"host-beacon").start();
    }
    void startPresenceBeacon(){
        new Thread(()->{
            try(DatagramSocket ds=new DatagramSocket()){
                ds.setBroadcast(true);
                while(running){
                    String ip=getLocalIp();
                    String mode=hosting?"HOST":(connected?"CONNECTED":"SEARCHING");
                    String msg="YUBARAJ_DEVICE:"+deviceName.replace("|"," ")+"|"+ip+"|"+mode;
                    byte[] data=msg.getBytes("UTF-8");
                    ds.send(new DatagramPacket(data,data.length,InetAddress.getByName("255.255.255.255"),DISCOVERY_PORT));
                    Thread.sleep(1800);
                }
            }catch(Exception ignored){}
        },"presence-beacon").start();
    }

    void sendBeacon(){
        try(DatagramSocket ds=new DatagramSocket()){
            ds.setBroadcast(true);
            while(hosting){
                String msg="YUBARAJ_HOST:"+getLocalIp()+"|"+deviceName.replace("|"," ");
                byte[] data=msg.getBytes("UTF-8");
                ds.send(new DatagramPacket(data,data.length,InetAddress.getByName("255.255.255.255"),DISCOVERY_PORT));
                Thread.sleep(1000);
            }
        }catch(Exception ignored){}
    }
    String getLocalIp(){
        try{
            Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
            while(en.hasMoreElements()){NetworkInterface ni=en.nextElement();Enumeration<InetAddress> as=ni.getInetAddresses();while(as.hasMoreElements()){InetAddress a=as.nextElement();if(!a.isLoopbackAddress()&&a instanceof Inet4Address){String ip=a.getHostAddress();if(ip.startsWith("192.168.")||ip.startsWith("10.")||ip.startsWith("172."))return ip;}}}
        }catch(Exception ignored){}
        return "192.168.43.1";
    }
    void startDiscoveryListener(){
        new Thread(()->{
            try(DatagramSocket ds=new DatagramSocket(null)){
                ds.setReuseAddress(true);ds.bind(new InetSocketAddress(DISCOVERY_PORT));ds.setBroadcast(true);byte[] buf=new byte[512];
                while(running){
                    DatagramPacket p=new DatagramPacket(buf,buf.length);ds.receive(p);
                    String msg=new String(p.getData(),p.getOffset(),p.getLength(),"UTF-8");
                    if(msg.startsWith("YUBARAJ_HOST:")&&!hosting){
                        String val=msg.substring("YUBARAJ_HOST:".length()).trim();String[] parts=val.split("\\|",2);
                        String ip=parts[0].trim();String name=parts.length>1?parts[1].trim():ip;
                        if(!ip.equals(getLocalIp())){
                            synchronized(peers){peers.put(ip,new Peer(ip,name,"HOST"));}
                            if(!connected&&!connecting)hostAddress=ip;
                            renderLists();
                        }
                    } else if(msg.startsWith("YUBARAJ_DEVICE:")){
                        String val=msg.substring("YUBARAJ_DEVICE:".length()).trim();String[] parts=val.split("\\|",3);
                        if(parts.length>=3){
                            String name=parts[0].trim(), ip=parts[1].trim(), mode=parts[2].trim();
                            if(!ip.equals(getLocalIp())){
                                synchronized(peers){
                                    Peer old=peers.get(ip);
                                    if(old==null || !"HOST".equals(old.mode)) peers.put(ip,new Peer(ip,name,mode));
                                    else {old.name=name; old.seen=System.currentTimeMillis();}
                                }
                                renderLists();
                            }
                        }
                    }
                }
            }catch(Exception e){setStatus("Wi-Fi discovery unavailable. Check hotspot/network permissions.");}
        },"discovery-listener").start();
    }
    void renderLists(){
        runOnUiThread(()->{
            if(wifiList==null||btList==null)return;
            wifiList.removeAllViews();
            ArrayList<Peer> list=new ArrayList<>();
            synchronized(peers){long now=System.currentTimeMillis();Iterator<Map.Entry<String,Peer>> it=peers.entrySet().iterator();while(it.hasNext()){Peer p=it.next().getValue();if(now-p.seen>12000)it.remove();else list.add(p);}}
            if(hosting){TextView own=new TextView(this);own.setText("🟢 "+deviceName+" (THIS PHONE — HOST)");own.setTextColor(Color.rgb(117,230,150));own.setPadding(10,8,10,8);wifiList.addView(own);}
            if(list.isEmpty()&&!hosting){TextView empty=new TextView(this);empty.setText("No app host found yet. Same Wi-Fi/Hotspot चाहिन्छ.");empty.setTextColor(Color.LTGRAY);empty.setPadding(8,8,8,8);wifiList.addView(empty);}
            for(Peer p:list){
                String icon="HOST".equals(p.mode)?"🟢":"🔵";
                String label=icon+" "+p.name+"   •   "+p.mode+"\\n"+p.ip;
                Button item=button(label);
                item.setGravity(Gravity.CENTER_VERTICAL);
                if("HOST".equals(p.mode)){
                    item.setOnClickListener(v->{hostAddress=p.ip;connectTo(p.ip);});
                } else {
                    item.setEnabled(false);
                }
                wifiList.addView(item,params());
            }
            btList.removeAllViews();
            if(bluetoothPeers.isEmpty()){TextView empty=new TextView(this);empty.setText("Bluetooth scan चलिरहेको छ…");empty.setTextColor(Color.LTGRAY);empty.setPadding(8,8,8,8);btList.addView(empty);}
            synchronized(bluetoothPeers){for(Map.Entry<String,String> e:bluetoothPeers.entrySet()){TextView item=new TextView(this);item.setText("🔵 "+e.getValue()+"  •  Bluetooth nearby");item.setTextColor(Color.rgb(150,205,255));item.setPadding(10,10,10,10);btList.addView(item);}}
        });
    }

    void startBluetoothScan(){
        if(btAdapter==null){setStatus("Bluetooth उपलब्ध छैन।");return;}
        try{
            if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED){requestNeededPermissions();return;}
            if(!btAdapter.isEnabled()){setStatus("Bluetooth scan का लागि Bluetooth ON गर्नुहोस्।");return;}
            if(btReceiver==null){
                btReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
                    String action=i.getAction();
                    if(BluetoothDevice.ACTION_FOUND.equals(action)){
                        BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if(d!=null){String name="Bluetooth device";try{if(Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED){if(d.getName()!=null)name=d.getName();}}catch(Exception ignored){}
                            String key=d.getAddress();bluetoothPeers.put(key,name);renderLists();}
                    } else if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED){setStatus("Search complete. Choose a Wi-Fi host to talk; Bluetooth list is discovery only.");}
                }};
                IntentFilter f=new IntentFilter();f.addAction(BluetoothDevice.ACTION_FOUND);f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);registerReceiver(btReceiver,f);
            }
            if(btAdapter.isDiscovering())btAdapter.cancelDiscovery();
            btAdapter.startDiscovery();
        }catch(SecurityException e){setStatus("Bluetooth permission चाहिन्छ।");}catch(Exception e){setStatus("Bluetooth search could not start.");}
    }

    void connectTo(String ip){
        if(hosting||connected||connecting){if(hosting)setStatus("This phone is the host.");return;}
        if(ip==null||ip.isEmpty())return;
        connecting=true;
        setStatus("Connecting to "+ip+"…");
        new Thread(()->{
            try{
                Socket s=new Socket();s.connect(new InetSocketAddress(ip,AUDIO_PORT),5000);s.setTcpNoDelay(true);
                clientSocket=s;clientOut=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));connected=true;connecting=false;
                runOnUiThread(()->{role.setText("CONNECTED TO "+ip);talkBtn.setEnabled(true);hostBtn.setEnabled(false);});
                setStatus("Connected. Hold HOLD TO TALK to speak.");
                readServer(s);
            }catch(Exception e){connected=false;connecting=false;setStatus("Connection failed. Confirm same hotspot and Host is running.");runOnUiThread(()->hostBtn.setEnabled(true));}
        },"connect-host").start();
    }
    void readClient(Client c){
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(c.socket.getInputStream()))){
            while(hosting&&!c.socket.isClosed()){
                int type=in.readUnsignedByte();int len=in.readInt();if(len<0||len>8192)break;byte[] data=new byte[len];in.readFully(data);
                if(type==1){play(data);for(Client other:clients)if(other!=c)other.send(type,data);}
            }
        }catch(Exception ignored){}finally{clients.remove(c);try{c.socket.close();}catch(Exception ignored){}if(hosting)setStatus("A device disconnected. Remaining: "+clients.size());}
    }
    void readServer(Socket s){
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()))){
            while(connected&&!s.isClosed()){
                int type=in.readUnsignedByte();int len=in.readInt();if(len<0||len>8192)break;byte[] data=new byte[len];in.readFully(data);if(type==1)play(data);
            }
        }catch(Exception ignored){}finally{if(!hosting){connected=false;setStatus("Disconnected from host.");runOnUiThread(()->{talkBtn.setEnabled(false);hostBtn.setEnabled(true);});}}
    }
    void startTalking(){
        if(talking)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestNeededPermissions();setStatus("Microphone permission is required.");return;}
        talking=true;setStatus("Talking… release the button to stop.");
        new Thread(()->{
            int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord rec=null;
            try{
                rec=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,CHUNK*4));
                if(rec.getState()!=AudioRecord.STATE_INITIALIZED)throw new Exception("Microphone could not start");
                rec.startRecording();byte[] b=new byte[CHUNK];
                while(talking){int n=rec.read(b,0,b.length);if(n>0){byte[] packet=Arrays.copyOf(b,n);if(hosting){for(Client c:clients)c.send(1,packet);}else if(clientOut!=null){synchronized(writeLock){clientOut.writeByte(1);clientOut.writeInt(n);clientOut.write(packet);clientOut.flush();}}}}
            }catch(Exception e){setStatus("Microphone error: "+e.getMessage());}
            finally{if(rec!=null){try{rec.stop();}catch(Exception ignored){}rec.release();}talking=false;}
        },"audio-capture").start();
    }
    void stopTalking(){talking=false;setStatus("Ready to talk.");}
    synchronized void play(byte[] pcm){
        try{
            if(player==null){
                int min=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                player=new AudioTrack(AudioManager.STREAM_MUSIC,SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,CHUNK*12),AudioTrack.MODE_STREAM);
                player.play();
            }
            player.write(pcm,0,pcm.length);
        }catch(Exception ignored){}
    }
    void disconnectAll(){
        talking=false;connected=false;hosting=false;connecting=false;
        try{if(serverSocket!=null)serverSocket.close();}catch(Exception ignored){}
        try{if(clientSocket!=null)clientSocket.close();}catch(Exception ignored){}
        for(Client c:clients)try{c.socket.close();}catch(Exception ignored){}clients.clear();
        runOnUiThread(()->{talkBtn.setEnabled(false);role.setText("Searching…");hostBtn.setEnabled(true);});
        setStatus("Disconnected. Auto search continues.");
    }
    @Override public void onDestroy(){
        running=false;disconnectAll();
        try{if(btAdapter!=null&&btAdapter.isDiscovering())btAdapter.cancelDiscovery();}catch(Exception ignored){}
        if(btReceiver!=null){try{unregisterReceiver(btReceiver);}catch(Exception ignored){}}
        if(player!=null){player.release();player=null;}
        if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();
        super.onDestroy();
    }
}
