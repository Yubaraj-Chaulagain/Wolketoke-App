package com.yubaraj.walkietalkie.relay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** TCP transport for a Wi-Fi/hotspot peer whose address and port are already known. */
public final class WifiRelayTransport implements RelayTransport {
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    public WifiRelayTransport(String host, int port, int timeoutMs) throws IOException {
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be 1..65535");
        if (timeoutMs < 0) throw new IllegalArgumentException("timeoutMs cannot be negative");

        Socket candidate = new Socket();
        try {
            candidate.connect(new InetSocketAddress(host.trim(), port), timeoutMs);
            candidate.setTcpNoDelay(true);
            candidate.setKeepAlive(true);
            DataInputStream input = new DataInputStream(new BufferedInputStream(candidate.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(candidate.getOutputStream()));
            socket = candidate;
            in = input;
            out = output;
        } catch (IOException | RuntimeException e) {
            try { candidate.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    @Override public String name() { return "WIFI"; }

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
        if (socket.isClosed() || !socket.isConnected()) throw new IOException("Wi-Fi relay socket is closed");
    }

    @Override public void close() throws IOException { socket.close(); }
}
