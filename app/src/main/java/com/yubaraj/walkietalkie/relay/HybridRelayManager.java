package com.yubaraj.walkietalkie.relay;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wi-Fi-first automatic failover with Bluetooth Classic fallback. The app supplies
 * factories because Wi-Fi peer discovery/addressing and Bluetooth pairing are user/device specific.
 * Run all operations off the main thread. One upstream link is active at a time.
 */
public final class HybridRelayManager implements AutoCloseable {
    public interface Factory { RelayTransport connect() throws IOException; }
    public interface Listener { void onTransportChanged(String transport); void onFrame(RelayFrame frame); void onError(String message); }
    private final Factory wifiFactory, bluetoothFactory; private final Listener listener;
    private final ExecutorService io=Executors.newSingleThreadExecutor(); private final AtomicBoolean running=new AtomicBoolean(false);
    private final Map<String,Boolean> seen=new LinkedHashMap<String,Boolean>(512,0.75f,true){
        protected boolean removeEldestEntry(Map.Entry<String,Boolean> e){ return size()>2048; }
    };
    private volatile RelayTransport active;
    public HybridRelayManager(Factory wifi, Factory bluetooth, Listener listener){wifiFactory=wifi;bluetoothFactory=bluetooth;this.listener=listener;}
    public void start(){ if(!running.compareAndSet(false,true)) return; io.execute(this::connectionLoop); }
    private void connectionLoop(){
        while(running.get()){
            RelayTransport t=null;
            try { t=tryConnect(wifiFactory); if(t==null) t=tryConnect(bluetoothFactory); if(t==null){sleep(2500);continue;}
                active=t; listener.onTransportChanged(t.name());
                while(running.get()){
                    RelayFrame f=t.receive(); if(f==null) throw new IOException("Peer disconnected");
                    synchronized(seen){ if(seen.put(f.packetId,Boolean.TRUE)!=null) continue; }
                    listener.onFrame(f);
                }
            } catch(Exception e){ if(running.get()) listener.onError(e.getMessage()==null?"Relay connection lost":e.getMessage()); }
            finally { active=null; if(t!=null) try{t.close();}catch(Exception ignored){} }
            sleep(1000);
        }
    }
    private RelayTransport tryConnect(Factory f){ if(f==null)return null; try{return f.connect();}catch(Exception ignored){return null;} }
    public void send(RelayFrame frame) throws IOException { RelayTransport t=active; if(t==null)throw new IOException("No relay link available"); t.send(frame); }
    /** Forward only frames not previously seen; packet IDs prevent loops across relay paths. */
    public void forward(RelayFrame frame, RelayTransport downstream) throws IOException {
        synchronized(seen){ if(seen.put(frame.packetId,Boolean.TRUE)!=null)return; } downstream.send(frame);
    }
    public String activeTransport(){RelayTransport t=active;return t==null?"DISCONNECTED":t.name();}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    public void close(){running.set(false); RelayTransport t=active;if(t!=null)try{t.close();}catch(Exception ignored){} io.shutdownNow();}
}
