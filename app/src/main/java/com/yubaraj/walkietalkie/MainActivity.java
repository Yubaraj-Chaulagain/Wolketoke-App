package com.yubaraj.walkietalkie;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Build;
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
    TextView status, role;
    Button hostBtn, findBtn, talkBtn, stopBtn;
    volatile boolean hosting=false, connected=false, talking=false, running=true;
    ServerSocket serverSocket;
    final List<Client> clients=new CopyOnWriteArrayList<>();
    Socket clientSocket;
    DataOutputStream clientOut;
    AudioTrack player;
    WifiManager.MulticastLock multicastLock;
    final Object writeLock=new Object();
    String hostAddress="";
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(16,24,39));
        getWindow().setNavigationBarColor(Color.rgb(16,24,39));
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},77);
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(wm!=null){ multicastLock=wm.createMulticastLock("yubaraj-walkie"); multicastLock.setReferenceCounted(false); multicastLock.acquire(); }
        buildUI();
        startDiscoveryListener();
    }
    void buildUI(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(28,30,28,24); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setBackgroundColor(Color.rgb(16,24,39));
        TextView title=new TextView(this); title.setText("Yubaraj Walkie-Talkie"); title.setTextColor(Color.WHITE); title.setTextSize(24); title.setGravity(Gravity.CENTER); title.setPadding(0,10,0,4); root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView sub=new TextView(this); sub.setText("Offline local Wi-Fi / Hotspot voice"); sub.setTextColor(Color.LTGRAY); sub.setGravity(Gravity.CENTER); sub.setPadding(0,0,0,25); root.addView(sub);
        role=new TextView(this); role.setText("Choose one phone as HOST"); role.setTextColor(Color.rgb(117,209,255)); role.setTextSize(16); role.setGravity(Gravity.CENTER); root.addView(role);
        hostBtn=button("START HOST"); findBtn=button("FIND HOST"); talkBtn=button("HOLD TO TALK"); stopBtn=button("DISCONNECT");
        root.addView(hostBtn,params()); root.addView(findBtn,params());
        talkBtn.setEnabled(false); talkBtn.setBackgroundColor(Color.rgb(43,62,88)); talkBtn.setOnTouchListener((v,e)->{
            if(!connected && !hosting){setStatus("Connect to a host first."); return true;}
            if(e.getAction()==MotionEvent.ACTION_DOWN){startTalking(); return true;}
            if(e.getAction()==MotionEvent.ACTION_UP || e.getAction()==MotionEvent.ACTION_CANCEL){stopTalking(); return true;}
            return true;
        });
        root.addView(talkBtn, new LinearLayout.LayoutParams(240,240)); root.addView(stopBtn,params());
        status=new TextView(this); status.setText("Ready. Enable one phone's hotspot, then connect all phones to it."); status.setTextColor(Color.WHITE); status.setTextSize(14); status.setGravity(Gravity.CENTER); status.setPadding(0,24,0,0); root.addView(status,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);
        hostBtn.setOnClickListener(v->startHost());
        findBtn.setOnClickListener(v->findHost());
        stopBtn.setOnClickListener(v->disconnectAll());
    }
    Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(43,62,88)));return b;}
    LinearLayout.LayoutParams params(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,8,0,8);return p;}
    void setStatus(String s){runOnUiThread(()->status.setText(s));}
    void startHost(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},77);setStatus("Allow microphone permission, then tap START HOST.");return;}
        if(hosting)return;
        hosting=true; connected=true; role.setText("HOST MODE"); talkBtn.setEnabled(true);
        new Thread(()->{
            try{
                serverSocket=new ServerSocket(AUDIO_PORT,20, new InetSocketAddress(AUDIO_PORT).getAddress());
                setStatus("Host running. Other phones: join this hotspot, then tap FIND HOST.");
                while(hosting){ Socket s=serverSocket.accept(); s.setTcpNoDelay(true); Client c=new Client(s); clients.add(c); setStatus("Host connected: "+clients.size()+" device(s)."); new Thread(()->readClient(c)).start(); }
            }catch(Exception e){if(hosting)setStatus("Host error: "+e.getMessage());}
        },"host-server").start();
        new Thread(this::sendBeacon,"host-beacon").start();
    }
    void sendBeacon(){
        try(DatagramSocket ds=new DatagramSocket()){
            ds.setBroadcast(true);
            while(hosting){
                byte[] data=("YUBARAJ_HOST:"+getLocalIp()).getBytes("UTF-8");
                DatagramPacket p=new DatagramPacket(data,data.length,InetAddress.getByName("255.255.255.255"),DISCOVERY_PORT);
                ds.send(p); Thread.sleep(1000);
            }
        }catch(Exception e){ }
    }
    String getLocalIp(){
        try{
            Enumeration<NetworkInterface> en=NetworkInterface.getNetworkInterfaces();
            while(en.hasMoreElements()){NetworkInterface ni=en.nextElement(); Enumeration<InetAddress> as=ni.getInetAddresses(); while(as.hasMoreElements()){InetAddress a=as.nextElement(); if(!a.isLoopbackAddress() && a instanceof Inet4Address){String ip=a.getHostAddress(); if(ip.startsWith("192.168.")||ip.startsWith("10.")||ip.startsWith("172."))return ip;}}}
        }catch(Exception e){}
        return "192.168.43.1";
    }
    void startDiscoveryListener(){
        new Thread(()->{
            try(DatagramSocket ds=new DatagramSocket(null)){
                ds.setReuseAddress(true); ds.bind(new InetSocketAddress(DISCOVERY_PORT)); ds.setBroadcast(true); byte[] buf=new byte[512];
                while(running){
                    DatagramPacket p=new DatagramPacket(buf,buf.length); ds.receive(p);
                    String msg=new String(p.getData(),p.getOffset(),p.getLength(),"UTF-8");
                    if(msg.startsWith("YUBARAJ_HOST:") && !hosting && !connected){
                        hostAddress=msg.substring("YUBARAJ_HOST:".length()).trim();
                        runOnUiThread(()->{role.setText("HOST FOUND: "+hostAddress);});
                    }
                }
            }catch(Exception e){ }
        },"discovery-listener").start();
    }
    void findHost(){
        if(hosting){setStatus("This phone is the host.");return;}
        if(!hostAddress.isEmpty()){connectTo(hostAddress);return;}
        setStatus("Searching local Wi-Fi for host… wait 3 seconds, then tap FIND HOST again.");
        new Thread(()->{
            try(DatagramSocket ds=new DatagramSocket()){
                ds.setBroadcast(true); byte[] b="YUBARAJ_DISCOVER".getBytes("UTF-8");
                ds.send(new DatagramPacket(b,b.length,InetAddress.getByName("255.255.255.255"),DISCOVERY_PORT));
            }catch(Exception e){}
        }).start();
    }
    void connectTo(String ip){
        setStatus("Connecting to "+ip+"…");
        new Thread(()->{
            try{
                Socket s=new Socket(); s.connect(new InetSocketAddress(ip,AUDIO_PORT),5000); s.setTcpNoDelay(true);
                clientSocket=s; clientOut=new DataOutputStream(new BufferedOutputStream(s.getOutputStream())); connected=true;
                runOnUiThread(()->{role.setText("CONNECTED: "+ip);talkBtn.setEnabled(true);});
                setStatus("Connected. Hold HOLD TO TALK to speak.");
                readServer(s);
            }catch(Exception e){connected=false;setStatus("Connection failed. Check same hotspot and retry. "+e.getMessage());}
        },"connect-host").start();
    }
    void readClient(Client c){
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(c.socket.getInputStream()))){
            while(hosting && !c.socket.isClosed()){
                int type=in.readUnsignedByte(); int len=in.readInt(); if(len<0||len>8192)break; byte[] data=new byte[len];in.readFully(data);
                if(type==1){play(data); for(Client other:clients)if(other!=c)other.send(type,data);}
            }
        }catch(Exception e){} finally{clients.remove(c);try{c.socket.close();}catch(Exception e){} if(hosting)setStatus("Device disconnected. "+clients.size()+" device(s) remain.");}
    }
    void readServer(Socket s){
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()))){
            while(connected && !s.isClosed()){
                int type=in.readUnsignedByte(); int len=in.readInt();if(len<0||len>8192)break;byte[] data=new byte[len];in.readFully(data);if(type==1)play(data);
            }
        }catch(Exception e){} finally{if(!hosting){connected=false;setStatus("Disconnected from host.");runOnUiThread(()->talkBtn.setEnabled(false));}}
    }
    void startTalking(){
        if(talking)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},77);setStatus("Microphone permission is required.");return;}
        talking=true; setStatus("Talking… release the button to stop.");
        new Thread(()->{
            int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord rec=null;
            try{
                rec=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,CHUNK*4));
                if(rec.getState()!=AudioRecord.STATE_INITIALIZED)throw new Exception("Microphone could not start");
                rec.startRecording(); byte[] b=new byte[CHUNK];
                while(talking){int n=rec.read(b,0,b.length);if(n>0){byte[] packet=Arrays.copyOf(b,n);if(hosting){for(Client c:clients)c.send(1,packet);play(packet);}else if(clientOut!=null){synchronized(writeLock){clientOut.writeByte(1);clientOut.writeInt(n);clientOut.write(packet);clientOut.flush();}}}}
            }catch(Exception e){setStatus("Microphone error: "+e.getMessage());}
            finally{if(rec!=null){try{rec.stop();}catch(Exception e){}rec.release();} talking=false;}
        },"audio-capture").start();
    }
    void stopTalking(){talking=false;setStatus("Ready to talk.");}
    synchronized void play(byte[] pcm){
        try{
            if(player==null){
                int min=AudioTrack.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                player=new AudioTrack(AudioManager.STREAM_VOICE_CALL,SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,CHUNK*8),AudioTrack.MODE_STREAM);
                player.play();
            }
            player.write(pcm,0,pcm.length);
        }catch(Exception e){}
    }
    void disconnectAll(){
        talking=false; connected=false; hosting=false;
        try{if(serverSocket!=null)serverSocket.close();}catch(Exception e){}
        try{if(clientSocket!=null)clientSocket.close();}catch(Exception e){}
        for(Client c:clients)try{c.socket.close();}catch(Exception e){} clients.clear();
        runOnUiThread(()->{talkBtn.setEnabled(false);role.setText("Disconnected");});
        setStatus("Disconnected. You can start host or find host again.");
    }
    @Override public void onDestroy(){running=false;disconnectAll();if(player!=null){player.release();player=null;}if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();super.onDestroy();}
    class Client{
        Socket socket; DataOutputStream out;
        Client(Socket s)throws Exception{socket=s;out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));}
        synchronized void send(int type,byte[] data){try{out.writeByte(type);out.writeInt(data.length);out.write(data);out.flush();}catch(Exception e){}}
    }
}
