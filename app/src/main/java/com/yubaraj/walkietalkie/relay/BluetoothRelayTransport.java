package com.yubaraj.walkietalkie.relay;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Bluetooth Classic RFCOMM transport for paired devices. */
public final class BluetoothRelayTransport implements RelayTransport {
    public static final UUID SERVICE_UUID = UUID.fromString("8f3a2f20-7c1b-4d91-9d0b-7f2c4e6a1001");

    private final BluetoothSocket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    private BluetoothRelayTransport(BluetoothSocket connectedSocket) throws IOException {
        socket = connectedSocket;
        try {
            in = new DataInputStream(new BufferedInputStream(connectedSocket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(connectedSocket.getOutputStream()));
        } catch (IOException e) {
            try { connectedSocket.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    /** Call from a worker thread. Android 12+ callers must hold BLUETOOTH_CONNECT. */
    public static BluetoothRelayTransport connect(BluetoothAdapter adapter, BluetoothDevice device) throws IOException {
        if (adapter == null) throw new IOException("Bluetooth adapter is unavailable");
        if (device == null) throw new IOException("Bluetooth peer is not selected");
        BluetoothSocket candidate = null;
        try {
            adapter.cancelDiscovery();
            candidate = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
            candidate.connect();
            return new BluetoothRelayTransport(candidate);
        } catch (SecurityException e) {
            closeQuietly(candidate);
            throw new IOException("Bluetooth permission is missing", e);
        } catch (IOException | RuntimeException e) {
            closeQuietly(candidate);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Unable to connect to Bluetooth peer", e);
        }
    }

    /** Waits for one paired peer. Run on a worker thread; close/cancel from caller to stop waiting. */
    public static BluetoothRelayTransport accept(BluetoothAdapter adapter, String serviceName) throws IOException {
        if (adapter == null) throw new IOException("Bluetooth adapter is unavailable");
        if (serviceName == null || serviceName.trim().isEmpty()) throw new IOException("Bluetooth service name is required");
        BluetoothServerSocket server = null;
        try {
            server = adapter.listenUsingRfcommWithServiceRecord(serviceName.trim(), SERVICE_UUID);
            BluetoothSocket accepted = server.accept();
            return new BluetoothRelayTransport(accepted);
        } catch (SecurityException e) {
            throw new IOException("Bluetooth permission is missing", e);
        } finally {
            if (server != null) try { server.close(); } catch (IOException ignored) { }
        }
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public String name() { return "BLUETOOTH"; }

    @Override public synchronized void send(RelayFrame frame) throws IOException {
        ensureOpen();
        if (frame == null) throw new IOException("Relay frame is null");
        frame.writeTo(out);
    }

    @Override public RelayFrame receive() throws IOException {
        ensureOpen();
        return RelayFrame.readFrom(in);
    }

    private void ensureOpen() throws IOException {
        try {
            if (socket.isClosed() || !socket.isConnected()) throw new IOException("Bluetooth relay socket is closed");
        } catch (SecurityException e) {
            throw new IOException("Bluetooth permission is missing", e);
        }
    }

    @Override public void close() throws IOException { socket.close(); }
}
