package com.yubaraj.walkietalkie.relay;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import java.io.*;
import java.util.UUID;

/** Bluetooth Classic RFCOMM stream for paired devices. Requires BLUETOOTH_CONNECT permission on Android 12+. */
public final class BluetoothRelayTransport implements RelayTransport {
    public static final UUID SERVICE_UUID = UUID.fromString("8f3a2f20-7c1b-4d91-9d0b-7f2c4e6a1001");
    private final BluetoothSocket socket; private final DataInputStream in; private final DataOutputStream out;
    private BluetoothRelayTransport(BluetoothSocket s) throws IOException {
        socket=s; in=new DataInputStream(new BufferedInputStream(s.getInputStream())); out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
    }
    public static BluetoothRelayTransport connect(BluetoothAdapter adapter, BluetoothDevice device) throws IOException {
        adapter.cancelDiscovery(); BluetoothSocket s=device.createRfcommSocketToServiceRecord(SERVICE_UUID); s.connect(); return new BluetoothRelayTransport(s);
    }
    public static BluetoothRelayTransport accept(BluetoothAdapter adapter, String serviceName) throws IOException {
        BluetoothServerSocket server=adapter.listenUsingRfcommWithServiceRecord(serviceName, SERVICE_UUID);
        try { return new BluetoothRelayTransport(server.accept()); } finally { server.close(); }
    }
    public String name(){ return "BLUETOOTH"; }
    public synchronized void send(RelayFrame f) throws IOException { f.writeTo(out); }
    public RelayFrame receive() throws IOException { return RelayFrame.readFrom(in); }
    public void close() throws IOException { socket.close(); }
}
