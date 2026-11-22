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

/**
 * Bluetooth Classic RFCOMM stream for paired devices.
 * Requires BLUETOOTH_CONNECT permission on Android 12+.
 */
public final class BluetoothRelayTransport implements RelayTransport {

    public static final UUID SERVICE_UUID =
            UUID.fromString("8f3a2f20-7c1b-4d91-9d0b-7f2c4e6a1001");

    private final BluetoothSocket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    private BluetoothRelayTransport(BluetoothSocket s)
            throws IOException {

        if (s == null) {
            throw new IOException("Bluetooth socket is null");
        }

        socket = s;

        in = new DataInputStream(
                new BufferedInputStream(s.getInputStream())
        );

        out = new DataOutputStream(
                new BufferedOutputStream(s.getOutputStream())
        );
    }

    /**
     * Connect to another paired Bluetooth device.
     */
    public static BluetoothRelayTransport connect(
            BluetoothAdapter adapter,
            BluetoothDevice device
    ) throws IOException {

        if (adapter == null || device == null) {
            throw new IOException("Bluetooth adapter or device is null");
        }

        adapter.cancelDiscovery();

        BluetoothSocket s =
                device.createRfcommSocketToServiceRecord(SERVICE_UUID);

        try {
            s.connect();

            if (!s.isConnected()) {
                throw new IOException("Bluetooth connection failed");
            }

            return new BluetoothRelayTransport(s);

        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }

            throw e;
        }
    }

    /**
     * Accept incoming Bluetooth connections.
     */
    public static BluetoothRelayTransport accept(
            BluetoothAdapter adapter,
            String serviceName
    ) throws IOException {

        if (adapter == null) {
            throw new IOException("Bluetooth adapter is null");
        }

        BluetoothServerSocket server =
                adapter.listenUsingRfcommWithServiceRecord(
                        serviceName,
                        SERVICE_UUID
                );

        try {
            BluetoothSocket client = server.accept();

            if (client == null || !client.isConnected()) {
                throw new IOException("Bluetooth client connection failed");
            }

            return new BluetoothRelayTransport(client);

        } finally {
            server.close();
        }
    }

    /**
     * Transport name.
     */
    @Override
    public String name() {
        return "BLUETOOTH";
    }

    /**
     * Send relay frame.
     */
    @Override
    public synchronized void send(RelayFrame frame)
            throws IOException {

        if (socket == null || !socket.isConnected()) {
            throw new IOException("Bluetooth relay socket is disconnected");
        }

        if (frame == null) {
            throw new IOException("Relay frame is null");
        }

        frame.writeTo(out);
    }

    /**
     * Receive relay frame.
     */
    @Override
    public RelayFrame receive() throws IOException {

        if (socket == null || !socket.isConnected()) {
            throw new IOException("Bluetooth relay socket is disconnected");
        }

        return RelayFrame.readFrom(in);
    }

    /**
     * Close Bluetooth connection.
     */
    @Override
    public void close() throws IOException {

        if (socket != null) {
            socket.close();
        }
    }
}
